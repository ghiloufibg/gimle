package com.gimle.ivaldi;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.web.BundledSpa;
import com.gimle.ivaldi.blueprint.BlueprintStore;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.run.RunController;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ivaldi's entry point: the cluster designer's backend. Configured either by flag ({@code --port},
 * {@code --data-root}, {@code --host}) or by the equivalent system property ({@code
 * -Dgimle.ivaldi.port}, {@code -Dgimle.ivaldi.dataRoot}, {@code -Dgimle.ivaldi.host}); a flag wins
 * over a property, since it is the more specific of the two on any one launch. Deliberately no
 * authentication or TLS, and bound to loopback unless a host is explicitly configured -- it is a
 * local development tool started next to a build, not a supervised cluster process.
 */
public final class IvaldiMain {

  private static final Logger log = LoggerFactory.getLogger(IvaldiMain.class);
  private static final int DEFAULT_PORT = 9097;

  private IvaldiMain() {}

  private static final String USAGE =
      """
      usage: ivaldi [--port <port>] [--data-root <dir>] [--host <host>]

        --port <port>       HTTP port to listen on (default 9097)
        --data-root <dir>   where blueprints, clusters and run workspaces are stored
                            (default ~/.gimle/ivaldi)
        --host <host>       address to bind (default loopback only -- binding anywhere else
                            exposes an unauthenticated, TLS-free designer to that network)
        --help              print this message

      Each flag has an equivalent system property (-Dgimle.ivaldi.port, -Dgimle.ivaldi.dataRoot,
      -Dgimle.ivaldi.host); the flag wins when both are given.
      """;

  public static void main(String[] args) throws IOException {
    if (List.of(args).contains("--help") || List.of(args).contains("-h")) {
      System.out.print(USAGE);
      return;
    }
    Map<String, String> flags = parseFlags(args);

    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Ivaldi",
            "app.description", "cluster designer backend",
            "app.version", GimleVersion.current()));
    System.setProperty("gimle.process.role", "IVALDI");

    int port = intSetting(flags, "--port", "gimle.ivaldi.port", DEFAULT_PORT);
    Path dataRoot =
        Path.of(
            setting(
                flags,
                "--data-root",
                "gimle.ivaldi.dataRoot",
                Path.of(System.getProperty("user.home"), ".gimle", "ivaldi").toString()));
    String host = setting(flags, "--host", "gimle.ivaldi.host", null);
    InetAddress address =
        host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host);

    if (isPortInUse(address, port)) {
      System.err.println(
          "ivaldi: port "
              + port
              + " on "
              + address.getHostAddress()
              + " is already in use -- choose another with --port");
      System.exit(1);
    }

    BlueprintStore store = new BlueprintStore(dataRoot.resolve("blueprints"));
    ClusterStore clusters = new ClusterStore(dataRoot.resolve("clusters"));
    RunController runs = new RunController(clusters, dataRoot);
    IvaldiServer server = new IvaldiServer(store, clusters, runs, address, port);

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

  /**
   * A pre-bind check purely so an already-occupied port is reported as one line naming the port,
   * rather than as a bare {@code BindException} stack trace printed under a banner that has already
   * claimed a successful start. The window between this and the real bind is irrelevant here --
   * losing it just restores the stack trace this avoids in the common case.
   */
  private static boolean isPortInUse(InetAddress address, int port) {
    if (port == 0) {
      return false;
    }
    try (ServerSocket probe = new ServerSocket(port, 0, address)) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  private static Map<String, String> parseFlags(String[] args) {
    Map<String, String> flags = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        throw new IllegalArgumentException("unexpected argument: " + arg + "\n\n" + USAGE);
      }
      int equals = arg.indexOf('=');
      if (equals > 0) {
        flags.put(arg.substring(0, equals), arg.substring(equals + 1));
        continue;
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(arg + " requires a value\n\n" + USAGE);
      }
      flags.put(arg, args[++i]);
    }
    for (String flag : flags.keySet()) {
      if (!List.of("--port", "--data-root", "--host").contains(flag)) {
        throw new IllegalArgumentException("unknown flag: " + flag + "\n\n" + USAGE);
      }
    }
    return flags;
  }

  private static String setting(
      Map<String, String> flags, String flag, String property, String fallback) {
    String value = flags.get(flag);
    return value != null ? value : System.getProperty(property, fallback);
  }

  private static int intSetting(
      Map<String, String> flags, String flag, String property, int fallback) {
    String value = setting(flags, flag, property, null);
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(flag + " is not a number: " + value);
    }
  }
}
