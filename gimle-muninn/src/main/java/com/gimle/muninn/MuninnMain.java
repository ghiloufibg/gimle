package com.gimle.muninn;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.net.DnsCacheTtl;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.authz.CertificateRotationAuditor;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.pki.CertificateRotationMonitor;
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

  /**
   * Backoff bounds for retrying an unresolvable store endpoint hostname at startup, matching the
   * node agent's own control-plane registration retry rather than inventing a second schedule.
   */
  private static final Duration RESOLUTION_INITIAL_BACKOFF = Duration.ofSeconds(1);

  private static final Duration RESOLUTION_MAX_BACKOFF = Duration.ofSeconds(30);

  private MuninnMain() {}

  public static void main(String[] args) throws IOException {
    DnsCacheTtl.apply();
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

    StoreClient storeClient = new StoreClient(awaitResolvableStoreEndpoints(storeEndpoints));
    MuninnServer muninnServer = new MuninnServer(storeClient, port, dataRoot);
    muninnServer.start();

    RetentionPolicy retentionPolicy = RetentionPolicy.fromConfig();
    Duration retentionSweepInterval =
        Duration.ofSeconds(Long.getLong("gimle.muninn.retentionSweepIntervalSeconds", 3600L));
    RetentionSweeper retentionSweeper =
        new RetentionSweeper(dataRoot, retentionPolicy, retentionSweepInterval);
    log.info(
        "retention windows (days): logs={}, metrics={}, traces={}, other={}",
        retentionPolicy.logsDays(),
        retentionPolicy.metricsDays(),
        retentionPolicy.tracesDays(),
        retentionPolicy.defaultDays());

    URI finalCsrEndpoint = csrEndpoint;
    // A failing rotation check is recorded durably at the start and the escalation point of a
    // failure streak, not just logged: a rotation that quietly stops working is harmless only
    // until the certificate it failed to renew expires. No meter registry here, unlike every other
    // process -- Muninn is the metrics sink and ships none of its own.
    OwnCertificateRotator certificateRotator =
        new OwnCertificateRotator(
            new CertificateRotationMonitor(
                "muninn",
                CERT_ROTATION_CHECK_INTERVAL,
                new CertificateRotationAuditor(storeClient, "muninn")));
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-muninn-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- Muninn has no leader election (stateless, N replicas),
    // and every replica's own certificate needs to stay fresh regardless. See rotationTick for
    // why each check is fully self-contained.
    ticker.scheduleAtFixedRate(
        () -> rotationTick(certificateRotator, finalCsrEndpoint, muninnServer),
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
   * A misconfiguration that no retry can fix (a missing required flag, an unusable data root) still
   * fails fast at startup, before this ticker is ever scheduled.
   *
   * <p>Package-visible so a test can drive a single tick directly.
   */
  static void rotationTick(
      OwnCertificateRotator certificateRotator, URI csrEndpoint, MuninnServer muninnServer) {
    try {
      if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
        return;
      }
      if (certificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), csrEndpoint).rotated()) {
        muninnServer.reloadTlsMaterial();
      }
    } catch (IOException | RuntimeException e) {
      log.warn(
          "certificate rotation check failed: {}; retrying at the next check",
          e.getMessage() == null ? e.getClass().getName() : e.getMessage(),
          e);
    }
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
