package com.gimle.fafnir;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.BundledSpa;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.observability.GimleTracing;
import com.gimle.observability.MuninnShipper;
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
 * Fafnir's entry point -- arg shape deliberately mirrors {@code ControlPlaneMain}, not {@code
 * StoreMain}: {@code --store-endpoints} is how Fafnir reaches {@code gimle-mimir}, exactly the same
 * way the control plane does, since both are stateless HTTP services sitting in front of the same
 * store cluster. {@code --csr-endpoint} is Fafnir's own rotation ticker's target, mirroring {@code
 * StoreMain}'s identical flag -- but not that class's ticker *ordering* bug (see the ticker below).
 */
public final class FafnirMain {

  private static final Logger log = LoggerFactory.getLogger(FafnirMain.class);
  private static final Duration CERT_ROTATION_CHECK_INTERVAL = Duration.ofSeconds(2);
  private static final Duration MUNINN_SHIP_INTERVAL = Duration.ofSeconds(5);

  private FafnirMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Fafnir",
            "app.description", "secrets vault",
            "app.version", GimleVersion.current()));
    if (args.length < 2) {
      System.err.println(
          "usage: FafnirMain <port> <secretKeyPath> --store-endpoints "
              + "host1:clientPort1,host2:clientPort2,... [--host <hostname>] "
              + "[--csr-endpoint <host:port>]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path secretKeyFilePath = Path.of(args[1]);
    String selfHost = "127.0.0.1";
    List<SocketAddress> storeEndpoints = List.of();
    URI csrEndpoint = null;
    for (int i = 2; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
      } else if ("--csr-endpoint".equals(args[i]) && i + 1 < args.length) {
        csrEndpoint = URI.create("https://" + args[++i] + "/bootstrap/csr");
      }
    }
    if (storeEndpoints.isEmpty()) {
      System.err.println("--store-endpoints is required (at least one host:clientPort)");
      System.exit(2);
      return;
    }

    System.setProperty("gimle.process.role", "FAFNIR");
    System.setProperty("gimle.node.id", selfHost + ":" + port);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("fafnir-platform.log"));

    // Same loud-banner posture ApiServer/ControlPlaneMain already carry for the identical
    // plaintext-by-default tradeoff -- see CLAUDE.md's "Not gaps" section. Fafnir's own exposure is
    // more sensitive than the control plane's (decrypted secret values, not just deployment state),
    // which is exactly why this banner names that explicitly rather than reusing generic wording.
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      log.warn(
          "running with no authentication (gimle.transport.protocol=plaintext) -- every"
              + " /internal/secrets/* and /secrets/rotate-key call on this port is unauthenticated"
              + " and can return or affect decrypted secret values; do not expose it beyond a"
              + " trusted local network. Set -Dgimle.transport.protocol=tls to require mTLS.");
    }

    StoreClient storeClient = new StoreClient(storeEndpoints);
    FafnirCrypto crypto = new FafnirCrypto(storeClient, secretKeyFilePath);
    FafnirServer fafnirServer = new FafnirServer(crypto, port);
    fafnirServer.start();

    // Optional system property, matching gimle-agent's own gimle.agent.muninnEndpoint pattern --
    // null means "ship nowhere," this replica's own request metrics simply aren't shipped
    // anywhere. Accepts a comma-separated list of Muninn replicas as well as a single endpoint.
    String muninnEndpoint = System.getProperty("gimle.fafnir.muninnEndpoint");
    List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpoint);
    MuninnShipper metricsShipper =
        muninnEndpoint == null
            ? null
            : new MuninnShipper(
                muninnEndpoints,
                "/ingest/metrics/FAFNIR/" + selfHost + ":" + fafnirServer.port(),
                MUNINN_SHIP_INTERVAL);
    if (metricsShipper != null) {
      metricsShipper.startShippingMetrics(fafnirServer.metrics().registry());
    }
    // Design doc Part B/O-13: a genuine RPC-serving process, unlike gimle-agent (see AgentMain's
    // own javadoc on why it deliberately skips tracing installation). Shipped to Muninn when
    // configured, falling back to GimleTracing's existing WorkerMain-established default
    // (LoggingSpanExporter) otherwise -- spans real and correctly parented either way.
    MuninnShipper tracesShipper =
        muninnEndpoint == null
            ? null
            : new MuninnShipper(
                muninnEndpoints,
                "/ingest/traces/FAFNIR/" + selfHost + ":" + fafnirServer.port(),
                MUNINN_SHIP_INTERVAL);
    if (tracesShipper != null) {
      GimleTracing.installWithMuninnShipping(tracesShipper);
    } else {
      GimleTracing.installDefault();
    }

    URI finalCsrEndpoint = csrEndpoint;
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-fafnir-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- Fafnir has no leader election (stateless, N replicas),
    // and every replica's own certificate needs to stay fresh regardless. Deliberately checks
    // TransportProtocol *before* touching TlsSettings.fromConfig(), unlike StoreMain's own ticker
    // (which evaluates TlsSettings.fromConfig() unconditionally and permanently kills this
    // scheduleAtFixedRate on its first tick in default plaintext mode, since
    // scheduleAtFixedRate suppresses all future runs after an uncaught exception) -- the same
    // correct ordering ApiServer/AgentMain already use.
    ticker.scheduleAtFixedRate(
        () -> {
          if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
            return;
          }
          boolean rotated =
              OwnCertificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), finalCsrEndpoint);
          if (rotated) {
            try {
              fafnirServer.reloadTlsMaterial();
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
        "fafnir listening on port {} (self: {}:{}, store endpoints: {})",
        fafnirServer.port(),
        selfHost,
        fafnirServer.port(),
        storeEndpoints);

    Optional<Path> consoleRoot =
        BundledSpa.resolve(FafnirMain.class.getClassLoader(), "fafnir-console/index.html");
    if (consoleRoot.isPresent()) {
      fafnirServer.serveConsole(consoleRoot.get());
      log.info("serving bundled web console at /console");
    } else {
      log.info("no bundled web console found on the classpath; /console disabled");
    }

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      fafnirServer.close();
                      ticker.shutdownNow();
                      storeClient.close();
                      if (metricsShipper != null) {
                        metricsShipper.close();
                      }
                      if (tracesShipper != null) {
                        tracesShipper.close();
                      }
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
