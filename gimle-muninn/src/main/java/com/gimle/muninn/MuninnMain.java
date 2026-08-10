package com.gimle.muninn;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Muninn's entry point -- arg shape mirrors {@code FafnirMain}'s ({@code --store-endpoints} is how
 * it reaches {@code gimle-mimir} for its own read-only {@code Authorizer} check on proxied reads),
 * plus one required flag Fafnir has no equivalent of: {@code --data-root}, where the logs/metrics/
 * traces this process ingests actually live on disk (see {@code OBSERVABILITY_AUDIT_DESIGN.md}
 * §5c).
 */
public final class MuninnMain {

  private static final Logger log = LoggerFactory.getLogger(MuninnMain.class);

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
    for (int i = 1; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
      } else if ("--data-root".equals(args[i]) && i + 1 < args.length) {
        dataRoot = Path.of(args[++i]);
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
    MuninnServer muninnServer = new MuninnServer(storeClient, port);
    muninnServer.start();

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
