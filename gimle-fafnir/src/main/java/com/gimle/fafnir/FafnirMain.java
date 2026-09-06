package com.gimle.fafnir;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.net.DnsCacheTtl;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.BundledSpa;
import com.gimle.mimir.authz.CertificateRotationAuditor;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.observability.CertificateRotationMetrics;
import com.gimle.observability.GimleTracing;
import com.gimle.observability.MuninnShipper;
import com.gimle.pki.CertificateRotationMonitor;
import com.gimle.pki.OwnCertificateRotator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

  /**
   * Backoff bounds for retrying an unresolvable store endpoint hostname at startup, matching the
   * node agent's own control-plane registration retry rather than inventing a second schedule.
   */
  private static final Duration RESOLUTION_INITIAL_BACKOFF = Duration.ofSeconds(1);

  private static final Duration RESOLUTION_MAX_BACKOFF = Duration.ofSeconds(30);

  private FafnirMain() {}

  public static void main(String[] args) throws IOException {
    DnsCacheTtl.apply();
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

    StoreClient storeClient = new StoreClient(awaitResolvableStoreEndpoints(storeEndpoints));
    FafnirCrypto crypto = new FafnirCrypto(storeClient, secretKeyFilePath);
    FafnirServer fafnirServer = new FafnirServer(crypto, port);
    fafnirServer.start();

    // Optional system property, matching gimle-agent's own gimle.agent.muninnEndpoint pattern --
    // null means "ship nowhere," this replica's own request metrics simply aren't shipped
    // anywhere. Accepts a comma-separated list of Muninn replicas as well as a single endpoint.
    String muninnEndpoint = System.getProperty("gimle.fafnir.muninnEndpoint");
    List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpoint);
    MuninnShipper metricsShipper =
        shipperFor(muninnEndpoints, "metrics", selfHost, fafnirServer.port());
    if (metricsShipper != null) {
      metricsShipper.startShippingMetrics(fafnirServer.metrics().registry());
    }
    // Tracing is installed here, unlike gimle-agent (see AgentMain's own javadoc on why it
    // deliberately skips tracing installation), because this is a genuine RPC-serving process, not
    // a pure supervisor. Shipped to Muninn when
    // configured, falling back to GimleTracing's existing WorkerMain-established default
    // (LoggingSpanExporter) otherwise -- spans real and correctly parented either way.
    MuninnShipper tracesShipper =
        shipperFor(muninnEndpoints, "traces", selfHost, fafnirServer.port());
    if (tracesShipper != null) {
      GimleTracing.installWithMuninnShipping(tracesShipper);
    } else {
      GimleTracing.installDefault();
    }

    URI finalCsrEndpoint = csrEndpoint;
    // Every rotation check -- including one that fails -- is metered into the registry already
    // shipped to Muninn and, at the start and the escalation point of a failure streak, appended to
    // the durable audit trail. A rotation that quietly stops working is harmless only until the
    // certificate it failed to renew expires, which is exactly the failure an operator must be able
    // to see coming.
    CertificateRotationMetrics certificateRotationMetrics =
        new CertificateRotationMetrics(fafnirServer.metrics().registry());
    OwnCertificateRotator certificateRotator =
        new OwnCertificateRotator(
            new CertificateRotationMonitor(
                "fafnir",
                CERT_ROTATION_CHECK_INTERVAL,
                new CertificateRotationAuditor(storeClient, "fafnir")
                    .andThen(
                        status ->
                            certificateRotationMetrics.recordCheck(
                                status.outcome().name(),
                                status.consecutiveFailures(),
                                status.remainingValidity(Instant.now())))));
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-fafnir-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- Fafnir has no leader election (stateless, N replicas),
    // and every replica's own certificate needs to stay fresh regardless. See rotationTick for
    // why each check is fully self-contained.
    ticker.scheduleAtFixedRate(
        () -> rotationTick(certificateRotator, finalCsrEndpoint, fafnirServer),
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

  /**
   * One certificate-rotation check, and the barrier that keeps the ticker above alive.
   *
   * <p>{@link ScheduledExecutorService#scheduleAtFixedRate} cancels a repeating task permanently
   * the moment one execution throws, so every failure reachable from here has to stay here. The
   * ones that are genuinely reachable are transient, not terminal: {@link TlsSettings#fromConfig()}
   * throws while the certificate or key file is momentarily absent (an external tool replacing the
   * pair is not atomic), and reloading the listener can fail on unreadable material that the next
   * attempt reads fine. Letting either escape would silently end certificate renewal for the rest
   * of this process's life -- harmless right up until the certificate it stopped renewing expires.
   * A misconfiguration that no retry can fix (a missing required flag, an unreadable key file at
   * boot) still fails fast at startup, before this ticker is ever scheduled.
   *
   * <p>Package-visible so a test can drive a single tick directly.
   */
  static void rotationTick(
      OwnCertificateRotator certificateRotator, URI csrEndpoint, FafnirServer fafnirServer) {
    try {
      if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
        return;
      }
      if (certificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), csrEndpoint).rotated()) {
        fafnirServer.reloadTlsMaterial();
      }
    } catch (IOException | RuntimeException e) {
      log.warn(
          "certificate rotation check failed: {}; retrying at the next check",
          e.getMessage() == null ? e.getClass().getName() : e.getMessage(),
          e);
    }
  }

  /**
   * A {@link MuninnShipper} targeting {@code /ingest/{kind}/FAFNIR/{selfHost}:{port}}, shipping to
   * every configured Muninn replica, or {@code null} when none are configured -- the shared shape
   * behind both the metrics and traces shippers above, which differ only in the ingest path's
   * {@code kind} segment.
   */
  private static MuninnShipper shipperFor(
      List<String> muninnEndpoints, String kind, String selfHost, int port) {
    return muninnEndpoints.isEmpty()
        ? null
        : new MuninnShipper(
            muninnEndpoints,
            "/ingest/" + kind + "/FAFNIR/" + selfHost + ":" + port,
            MUNINN_SHIP_INTERVAL);
  }

  /**
   * Re-resolves every store endpoint whose hostname did not resolve, retrying with capped
   * exponential backoff until it does.
   *
   * <p>An {@link InetSocketAddress} resolves its hostname once, when it is constructed, and keeps
   * that answer for good. A name that happens to be unresolvable in the second this process starts
   * -- a DNS server still coming up, a record not yet propagated, a momentary resolver failure --
   * would therefore leave this process holding a permanently unresolved address and failing every
   * store call for the rest of its life, long after the name started resolving again. Waiting here
   * makes a transient name-resolution failure exactly that: transient. Waiting is also why this
   * loop has no attempt ceiling -- a store this process cannot name is a store it cannot authorize
   * a single request against, so serving anyway would only answer every caller with an error. A
   * malformed endpoint spec is different, and still fails fast in {@link #parseStoreEndpoints}: no
   * amount of retrying turns it into an address.
   */
  static List<SocketAddress> awaitResolvableStoreEndpoints(List<SocketAddress> endpoints) {
    List<SocketAddress> resolved = new ArrayList<>();
    for (SocketAddress endpoint : endpoints) {
      resolved.add(
          endpoint instanceof InetSocketAddress inet && inet.isUnresolved()
              ? resolveWithRetry(inet.getHostString(), inet.getPort())
              : endpoint);
    }
    return List.copyOf(resolved);
  }

  private static InetSocketAddress resolveWithRetry(String host, int port) {
    Duration backoff = RESOLUTION_INITIAL_BACKOFF;
    for (int attempt = 1; ; attempt++) {
      InetSocketAddress address = new InetSocketAddress(host, port);
      if (!address.isUnresolved()) {
        if (attempt > 1) {
          log.info("store endpoint host {} resolved after {} attempts", host, attempt);
        }
        return address;
      }
      log.warn(
          "could not resolve store endpoint host {} (attempt {}) -- retrying in {}",
          host,
          attempt,
          backoff);
      try {
        Thread.sleep(backoff);
      } catch (InterruptedException e) {
        // Only reachable while the process is being torn down, where the address this returns is
        // about to stop mattering; the flag is restored so the shutdown keeps propagating.
        Thread.currentThread().interrupt();
        return address;
      }
      backoff = nextResolutionBackoff(backoff);
    }
  }

  static Duration nextResolutionBackoff(Duration current) {
    Duration doubled = current.multipliedBy(2);
    return doubled.compareTo(RESOLUTION_MAX_BACKOFF) > 0 ? RESOLUTION_MAX_BACKOFF : doubled;
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
