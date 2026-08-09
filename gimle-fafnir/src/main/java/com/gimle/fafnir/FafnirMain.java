package com.gimle.fafnir;

import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fafnir's entry point -- arg shape deliberately mirrors {@code ControlPlaneMain}, not {@code
 * StoreMain}: {@code --store-endpoints} is how Fafnir reaches {@code gimle-mimir}, exactly the same
 * way the control plane does, since both are stateless HTTP services sitting in front of the same
 * store cluster. {@code --csr-endpoint} (TLS certificate bootstrap/rotation) lands in a later phase
 * of the migration plan, not this one -- this entry point runs in plaintext-by-default mode like
 * every other Gimlé process until that phase wires it in.
 */
public final class FafnirMain {

  private static final Logger log = LoggerFactory.getLogger(FafnirMain.class);

  private FafnirMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println(
          "usage: FafnirMain <port> <secretKeyPath> --store-endpoints "
              + "host1:clientPort1,host2:clientPort2,... [--host <hostname>]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path secretKeyFilePath = Path.of(args[1]);
    String selfHost = "127.0.0.1";
    List<SocketAddress> storeEndpoints = List.of();
    for (int i = 2; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
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

    log.info(
        "fafnir listening on port {} (self: {}:{}, store endpoints: {})",
        fafnirServer.port(),
        selfHost,
        fafnirServer.port(),
        storeEndpoints);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      fafnirServer.close();
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
