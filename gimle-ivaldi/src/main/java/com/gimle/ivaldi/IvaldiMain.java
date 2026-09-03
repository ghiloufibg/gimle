package com.gimle.ivaldi;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.web.BundledSpa;
import com.gimle.ivaldi.blueprint.BlueprintStore;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ivaldi's entry point: the cluster designer's backend, configured entirely through system
 * properties ({@code -Dgimle.ivaldi.port}, {@code -Dgimle.ivaldi.dataRoot}, {@code
 * -Dgimle.ivaldi.host}) rather than the flag parsing the cluster processes use -- it is a local
 * development tool started next to a build, not a supervised cluster process. Deliberately no
 * authentication or TLS, and bound to loopback unless a host is explicitly configured.
 */
public final class IvaldiMain {

  private static final Logger log = LoggerFactory.getLogger(IvaldiMain.class);
  private static final int DEFAULT_PORT = 9097;

  private IvaldiMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Ivaldi",
            "app.description", "cluster designer backend",
            "app.version", GimleVersion.current()));
    System.setProperty("gimle.process.role", "IVALDI");

    int port = Integer.getInteger("gimle.ivaldi.port", DEFAULT_PORT);
    Path dataRoot =
        Path.of(
            System.getProperty(
                "gimle.ivaldi.dataRoot",
                Path.of(System.getProperty("user.home"), ".gimle", "ivaldi").toString()));
    String host = System.getProperty("gimle.ivaldi.host");
    InetAddress address =
        host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host);

    BlueprintStore store = new BlueprintStore(dataRoot.resolve("blueprints"));
    IvaldiServer server = new IvaldiServer(store, address, port);

    Optional<Path> consoleRoot =
        BundledSpa.resolve(IvaldiMain.class.getClassLoader(), "ivaldi-console/index.html");
    if (consoleRoot.isPresent()) {
      server.serveConsole(consoleRoot.get());
    }

    server.start();
    log.info(
        "ivaldi listening on {}:{} with no authentication or TLS -- a local development tool,"
            + " loopback-bound unless -Dgimle.ivaldi.host says otherwise (data root: {})",
        address.getHostAddress(),
        server.port(),
        dataRoot);
    if (consoleRoot.isPresent()) {
      log.info("serving bundled web console at /console");
    } else {
      log.info("no bundled web console found on the classpath; /console disabled");
    }

    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
  }
}
