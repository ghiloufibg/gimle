package com.gimle.andvari;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.BundledSpa;
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
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Andvari's entry point -- arg shape mirrors {@code MuninnMain}'s exactly ({@code
 * --store-endpoints} is how it reaches {@code gimle-mimir} for its own independent {@code
 * Authorizer} re-check on artifact pushes and deletes, {@code --data-root} is where pushed jars
 * live, {@code --csr-endpoint} is the rotation ticker's target), because the two processes have the
 * same shape: a stateless HTTP surface over a directory of files, authorized against store-held
 * RBAC data.
 */
public final class AndvariMain {

  private static final Logger log = LoggerFactory.getLogger(AndvariMain.class);
  private static final Duration CERT_ROTATION_CHECK_INTERVAL = Duration.ofSeconds(2);

  private AndvariMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Andvari",
            "app.description", "module artifact registry",
            "app.version", GimleVersion.current()));
    if (args.length < 1) {
      System.err.println(
          "usage: AndvariMain <port> --store-endpoints host1:clientPort1,... --data-root <path> "
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

    System.setProperty("gimle.process.role", "ANDVARI");
    System.setProperty("gimle.node.id", selfHost + ":" + port);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("andvari-platform.log"));

    // Same loud-banner posture every other process here carries for the identical
    // plaintext-by-default tradeoff. Andvari's exposure is worse than most: an unauthenticated
    // push places an executable module jar where node agents will pull and run it, so the warning
    // names that supply-chain consequence explicitly rather than reusing generic wording.
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      log.warn(
          "running with no authentication (gimle.transport.protocol=plaintext) -- every"
              + " /artifacts/* call on this port is unauthenticated: anyone reaching it can push"
              + " executable module jars that node agents will download and run, or delete stored"
              + " ones; do not expose it beyond a trusted local network. Set"
              + " -Dgimle.transport.protocol=tls to require mTLS.");
    }

    StoreClient storeClient = new StoreClient(storeEndpoints);
    AndvariServer andvariServer = new AndvariServer(storeClient, port, dataRoot);
    andvariServer.start();

    URI finalCsrEndpoint = csrEndpoint;
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-andvari-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- Andvari has no leader election, and its certificate needs
    // to stay fresh regardless. Deliberately checks TransportProtocol *before* touching
    // TlsSettings.fromConfig(), the same correct ordering FafnirMain/MuninnMain/ApiServer/AgentMain
    // already use -- StoreMain's own ticker evaluates TlsSettings.fromConfig() unconditionally
    // instead, which permanently kills a scheduleAtFixedRate on its first tick in default
    // plaintext mode (TlsSettings.fromConfig() throws, and scheduleAtFixedRate suppresses all
    // future runs after an uncaught exception).
    ticker.scheduleAtFixedRate(
        () -> {
          if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
            return;
          }
          boolean rotated =
              OwnCertificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), finalCsrEndpoint);
          if (rotated) {
            try {
              andvariServer.reloadTlsMaterial();
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
        "andvari listening on port {} (self: {}:{}, data root: {}, store endpoints: {})",
        andvariServer.port(),
        selfHost,
        andvariServer.port(),
        dataRoot,
        storeEndpoints);

    Optional<Path> consoleRoot =
        BundledSpa.resolve(AndvariMain.class.getClassLoader(), "andvari-console/index.html");
    if (consoleRoot.isPresent()) {
      andvariServer.serveConsole(consoleRoot.get());
      log.info("serving bundled web console at /console");
    } else {
      log.info("no bundled web console found on the classpath; /console disabled");
    }

    // Off by default -- a full-store digest walk is real, if modest, disk/CPU cost, and
    // handleDownload's own per-request re-check already covers every coordinate that actually
    // gets pulled. Opt in with -Dgimle.andvari.scrub.enabled=true on a node where catching bit
    // rot on rarely-downloaded versions is worth paying that cost proactively rather than only
    // discovering it the next time someone happens to GET the corrupted one.
    IntegrityScrubber integrityScrubber = null;
    if (Boolean.getBoolean("gimle.andvari.scrub.enabled")) {
      Duration scrubInterval =
          Duration.ofSeconds(Long.getLong("gimle.andvari.scrub.intervalSeconds", 86_400L));
      integrityScrubber =
          new IntegrityScrubber(
              andvariServer.artifactStore(), andvariServer::reportIntegrityFailure, scrubInterval);
      log.info("integrity scrub enabled, running every {}", scrubInterval);
    } else {
      log.info("integrity scrub disabled (set -Dgimle.andvari.scrub.enabled=true to enable)");
    }

    IntegrityScrubber finalIntegrityScrubber = integrityScrubber;
    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      andvariServer.close();
                      ticker.shutdownNow();
                      if (finalIntegrityScrubber != null) {
                        finalIntegrityScrubber.close();
                      }
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
