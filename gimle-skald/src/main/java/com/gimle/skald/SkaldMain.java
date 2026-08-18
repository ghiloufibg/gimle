package com.gimle.skald;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.skald.directory.CachingServiceDirectory;
import com.gimle.skald.directory.ControlPlaneServicePoller;
import com.gimle.skald.directory.HttpServiceCatalogClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
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

  private SkaldMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Skald",
            "app.description", "cluster DNS server",
            "app.version", GimleVersion.current()));
    if (args.length < 1) {
      System.err.println(
          "usage: SkaldMain <dnsPort> --control-plane-endpoint <host:port>"
              + " [--poll-interval-seconds N]");
      System.exit(2);
      return;
    }
    int dnsPort = Integer.parseInt(args[0]);
    String controlPlaneEndpoint = null;
    Duration pollInterval = DEFAULT_POLL_INTERVAL;
    for (int i = 1; i < args.length; i++) {
      if ("--control-plane-endpoint".equals(args[i]) && i + 1 < args.length) {
        controlPlaneEndpoint = args[++i];
      } else if ("--poll-interval-seconds".equals(args[i]) && i + 1 < args.length) {
        pollInterval = Duration.ofSeconds(Long.parseLong(args[++i]));
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
    SkaldServer server = new SkaldServer(directory, dnsPort);

    log.info(
        "skald listening for DNS queries on UDP port {} (control plane: {}, poll interval: {})",
        server.port(),
        controlPlaneEndpoint,
        pollInterval);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      server.close();
                      poller.close();
                    }));
  }
}
