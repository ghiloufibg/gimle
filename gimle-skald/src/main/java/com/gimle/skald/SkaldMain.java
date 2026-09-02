package com.gimle.skald;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.net.DnsCacheTtl;
import com.gimle.observability.MuninnShipper;
import com.gimle.skald.directory.CachingServiceDirectory;
import com.gimle.skald.directory.ControlPlaneServicePoller;
import com.gimle.skald.directory.HttpServiceCatalogClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skald's entry point: binds the UDP DNS responder and starts the control-plane poller that feeds
 * it. Unlike every other Gimle process kind, Skald's own client-facing protocol is DNS-over-UDP
 * itself, which has no TLS story to opt into the way an HTTP-based process does -- so there is no
 * plaintext-warning banner here, and its polling connection to the control plane's HTTP API stays
 * plain HTTP for this first slice rather than growing an mTLS path of its own (matching how a new
 * component in this codebase typically starts plaintext-only before a transport-security pass, the
 * same way {@code AndvariMain}'s own {@code --peer-endpoints} sync started before its TLS support
 * landed).
 */
public final class SkaldMain {

  private static final Logger log = LoggerFactory.getLogger(SkaldMain.class);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
  private static final Duration MUNINN_SHIP_INTERVAL = Duration.ofSeconds(5);

  /**
   * See {@code SkaldServer.DEFAULT_STALE_THRESHOLD}'s own javadoc for the reasoning behind six poll
   * cycles specifically -- this is what turns that multiplier into an absolute threshold scaled to
   * whatever poll interval this process actually started with.
   */
  private static final int STALE_THRESHOLD_POLL_MULTIPLIER = 6;

  private SkaldMain() {}

  public static void main(String[] args) throws IOException {
    DnsCacheTtl.apply();
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Skald",
            "app.description", "cluster DNS server",
            "app.version", GimleVersion.current()));
    if (args.length < 1) {
      System.err.println(
          "usage: SkaldMain <dnsPort> --control-plane-endpoint <host:port>"
              + " [--poll-interval-seconds N] [--host <hostname>]"
              + " [--health-port N] [--muninn-endpoint host:port[,host:port...]]");
      System.exit(2);
      return;
    }
    int dnsPort = Integer.parseInt(args[0]);
    String controlPlaneEndpoint = null;
    String selfHost = "127.0.0.1";
    Duration pollInterval = DEFAULT_POLL_INTERVAL;
    String muninnEndpointArg = System.getProperty("gimle.skald.muninnEndpoint");
    // Absent means no health surface at all -- a standalone Skald process nobody probes needs
    // none, and opening a port it never uses would be gratuitous.
    int healthPort = -1;
    for (int i = 1; i < args.length; i++) {
      if ("--control-plane-endpoint".equals(args[i]) && i + 1 < args.length) {
        controlPlaneEndpoint = args[++i];
      } else if ("--poll-interval-seconds".equals(args[i]) && i + 1 < args.length) {
        pollInterval = Duration.ofSeconds(Long.parseLong(args[++i]));
      } else if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--health-port".equals(args[i]) && i + 1 < args.length) {
        healthPort = Integer.parseInt(args[++i]);
      } else if ("--muninn-endpoint".equals(args[i]) && i + 1 < args.length) {
        muninnEndpointArg = args[++i];
      }
    }
    if (controlPlaneEndpoint == null || controlPlaneEndpoint.isBlank()) {
      System.err.println("--control-plane-endpoint is required (host:port)");
      System.exit(2);
      return;
    }

    System.setProperty("gimle.process.role", "SKALD");
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("skald-platform.log"));

    URI controlPlaneUri = URI.create("http://" + controlPlaneEndpoint + "/");
    HttpServiceCatalogClient catalogClient =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), controlPlaneUri);
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(catalogClient, directory, pollInterval);
    Duration staleThreshold = pollInterval.multipliedBy(STALE_THRESHOLD_POLL_MULTIPLIER);
    SkaldServer server = new SkaldServer(directory, dnsPort, staleThreshold);

    // Only bound when asked for. What makes Skald probe-able as a managed workload: the platform's
    // vessel probes speak TCP and HTTP, neither of which can reach a DNS-over-UDP responder, so a
    // deployed Skald would otherwise have no health signal above "the process is still running".
    SkaldHealthServer healthServer =
        healthPort < 0 ? null : new SkaldHealthServer(directory, healthPort, staleThreshold);
    if (healthServer != null) {
      log.info(
          "skald serving /health and /ready on HTTP port {} (readiness threshold: {})",
          healthServer.port(),
          staleThreshold);
    }

    SkaldMetrics metrics = new SkaldMetrics(directory);
    // Optional system property/flag, matching gimle-fafnir's own gimle.fafnir.muninnEndpoint
    // pattern -- null means "ship nowhere," the staleness/failure gauges above are still live on
    // metrics.registry() for anything else that wants to read them, just not shipped anywhere.
    List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpointArg);
    MuninnShipper metricsShipper =
        muninnEndpoints.isEmpty()
            ? null
            : new MuninnShipper(
                muninnEndpoints,
                "/ingest/metrics/SKALD/" + selfHost + ":" + dnsPort,
                MUNINN_SHIP_INTERVAL);
    if (metricsShipper != null) {
      metricsShipper.startShippingMetrics(metrics.registry());
    }

    log.info(
        "skald listening for DNS queries on UDP port {} (control plane: {}, poll interval: {},"
            + " stale threshold: {})",
        server.port(),
        controlPlaneEndpoint,
        pollInterval,
        staleThreshold);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      server.close();
                      poller.close();
                      if (healthServer != null) {
                        healthServer.close();
                      }
                      if (metricsShipper != null) {
                        metricsShipper.close();
                      }
                    }));
  }
}
