package com.gimle.muninn;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.pki.OwnCertificateRotator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Muninn's entry point -- arg shape mirrors {@code FafnirMain}'s ({@code --store-endpoints} is how
 * it reaches {@code gimle-mimir} for its own read-only {@code Authorizer} check on proxied reads,
 * {@code --csr-endpoint} is this process's own rotation ticker's target, mirroring {@code
 * FafnirMain}'s identical flag -- and that class's ticker *ordering*, not {@code StoreMain}'s, see
 * the ticker below), plus one required flag Fafnir has no equivalent of: {@code --data-root}, the
 * directory this process's ingested logs/metrics/traces are stored under.
 */
public final class MuninnMain {

  private static final Logger log = LoggerFactory.getLogger(MuninnMain.class);
  private static final Duration CERT_ROTATION_CHECK_INTERVAL = Duration.ofSeconds(2);

  private MuninnMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Muninn",
            "app.description", "logs, metrics, and traces sink",
            "app.version", GimleVersion.current()));
    if (args.length < 1) {
      System.err.println(
          "usage: MuninnMain <port> --store-endpoints host1:clientPort1,... --data-root <path> "
              + "[--host <hostname>] [--csr-endpoint <host:port>]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    String selfHost = "127.0.0.1";
    List<SocketAddress> storeEndpoints = List.of();
    Path dataRoot = null;
    URI csrEndpoint = null;
    for (int i = 1; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
      } else if ("--data-root".equals(args[i]) && i + 1 < args.length) {
        dataRoot = Path.of(args[++i]);
      } else if ("--csr-endpoint".equals(args[i]) && i + 1 < args.length) {
        csrEndpoint = URI.create("https://" + args[++i] + "/bootstrap/csr");
      }
    }
    if (storeEndpoints.isEmpty()) {
      System.err.println("--store-endpoints is required (at least one host:clientPort)");
      System.exit(2);
      return;
    }
    if (dataRoot == null) {
      System.err.println("--data-root is required");
      System.exit(2);
      return;
    }

    System.setProperty("gimle.process.role", "MUNINN");
    System.setProperty("gimle.node.id", selfHost + ":" + port);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("muninn-platform.log"));

    // Same loud-banner posture every other process here carries for the identical
    // plaintext-by-default tradeoff -- see CLAUDE.md's "Not gaps" section. Muninn's own exposure
    // is read/write access to shipped logs/metrics/traces, not decrypted secrets, but still worth
    // calling out explicitly rather than reusing generic wording.
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      log.warn(
          "running with no authentication (gimle.transport.protocol=plaintext) -- every"
              + " /ingest/* and /*-history/* call on this port is unauthenticated and can write or"
              + " read shipped logs, metrics, and traces; do not expose it beyond a trusted local"
              + " network. Set -Dgimle.transport.protocol=tls to require mTLS.");
    }

    StoreClient storeClient = new StoreClient(storeEndpoints);
    MuninnServer muninnServer = new MuninnServer(storeClient, port, dataRoot);
    muninnServer.start();

    int retentionDays = Integer.getInteger("gimle.muninn.retentionDays", 30);
    Duration retentionSweepInterval =
        Duration.ofSeconds(Long.getLong("gimle.muninn.retentionSweepIntervalSeconds", 3600L));
    RetentionSweeper retentionSweeper =
        new RetentionSweeper(dataRoot, retentionDays, retentionSweepInterval);

    URI finalCsrEndpoint = csrEndpoint;
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-muninn-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- Muninn has no leader election (stateless, N replicas),
    // and every replica's own certificate needs to stay fresh regardless. Deliberately checks
    // TransportProtocol *before* touching TlsSettings.fromConfig(), the same correct ordering
    // FafnirMain/ApiServer/AgentMain already use -- StoreMain's own ticker evaluates
    // TlsSettings.fromConfig() unconditionally instead, which permanently kills a
    // scheduleAtFixedRate on its first tick in default plaintext mode (TlsSettings.fromConfig()
    // throws, and scheduleAtFixedRate suppresses all future runs after an uncaught exception).
    ticker.scheduleAtFixedRate(
        () -> {
          if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
            return;
          }
          boolean rotated =
              OwnCertificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), finalCsrEndpoint);
          if (rotated) {
            try {
              muninnServer.reloadTlsMaterial();
            } catch (IOException e) {
              log.warn(
                  "failed to reload TLS material after certificate rotation: {}", e.getMessage());
            }
          }
        },
        CERT_ROTATION_CHECK_INTERVAL.toMillis(),
        CERT_ROTATION_CHECK_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);

    log.info(
        "muninn listening on port {} (self: {}:{}, data root: {}, store endpoints: {})",
        muninnServer.port(),
        selfHost,
        muninnServer.port(),
        dataRoot,
        storeEndpoints);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      muninnServer.close();
                      ticker.shutdownNow();
                      retentionSweeper.close();
                      storeClient.close();
                    }));
  }

  private static List<SocketAddress> parseStoreEndpoints(String spec) {
    if (spec == null || spec.isBlank()) {
      return List.of();
    }
    List<SocketAddress> endpoints = new ArrayList<>();
    for (String entry : spec.split(",")) {
      int colon = entry.lastIndexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException(
            "malformed --store-endpoints entry (expected host:clientPort): " + entry);
      }
      String host = entry.substring(0, colon);
      int clientPort = Integer.parseInt(entry.substring(colon + 1));
      endpoints.add(new InetSocketAddress(host, clientPort));
    }
    return endpoints;
  }
}
