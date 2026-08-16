package com.gimle.saga;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.web.BundledSpa;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saga's entry point: the test-report server, configured entirely through system properties ({@code
 * -Dgimle.saga.port}, {@code -Dgimle.saga.dataRoot}, {@code -Dgimle.saga.host}) rather than the
 * flag parsing the cluster processes use -- it is a local development tool started next to a build,
 * not a supervised cluster process. Deliberately no authentication or TLS, and bound to loopback
 * unless a host is explicitly configured.
 */
public final class SagaMain {

  private static final Logger log = LoggerFactory.getLogger(SagaMain.class);
  private static final int DEFAULT_PORT = 9096;

  private SagaMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Saga",
            "app.description", "test run report server",
            "app.version", GimleVersion.current()));
    System.setProperty("gimle.process.role", "SAGA");

    int port = Integer.getInteger("gimle.saga.port", DEFAULT_PORT);
    Path dataRoot =
        Path.of(
            System.getProperty(
                "gimle.saga.dataRoot",
                Path.of(System.getProperty("user.home"), ".gimle", "saga").toString()));
    String host = System.getProperty("gimle.saga.host");
    InetAddress address =
        host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host);

    SagaStore store = new SagaStore(dataRoot);
    SagaServer server = new SagaServer(store, address, port);

    Optional<Path> consoleRoot =
        BundledSpa.resolve(SagaMain.class.getClassLoader(), "saga-console/index.html");
    if (consoleRoot.isPresent()) {
      server.serveConsole(consoleRoot.get());
    }

    server.start();
    log.info(
        "saga listening on {}:{} with no authentication or TLS -- a local development tool,"
            + " loopback-bound unless -Dgimle.saga.host says otherwise (data root: {})",
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
