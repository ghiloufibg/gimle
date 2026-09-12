package com.gimle.hilmir.launch.fixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds a listening socket like {@link SocketFixtureMain}, but first spawns a real child OS process
 * running {@link ChildFixtureMain} -- giving hilmir's own descendant-kill tests a genuine process
 * tree (this process's own pid, plus a real child pid beneath it) without needing an actual Gimlé
 * agent/worker pair to exercise the same shape.
 *
 * <p>When this process itself was given a {@code -Dgimle.log.root} (the same property a real
 * {@code AgentMain} always carries), it forwards {@code <that>/workers/child} to its own child
 * exactly the way {@code AgentMain#buildWorkerCommand} scopes a real worker's log root under its
 * agent's -- so a test can exercise {@code MachineLauncher}'s orphaned-worker discovery against the
 * real marker convention rather than a fake stand-in. Left off entirely when unset, so the existing
 * plain descendant-kill tests (which never set it) spawn a child with nothing extra to match on.
 */
public final class ParentWithChildFixtureMain {

  private ParentWithChildFixtureMain() {}

  public static void main(final String[] args) throws IOException {
    final int port = Integer.parseInt(args[0]);
    final boolean childIgnoresSigterm = Boolean.parseBoolean(args[1]);
    final String agentLogRoot = System.getProperty("gimle.log.root");
    final List<String> childCommand = new ArrayList<>();
    childCommand.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    if (agentLogRoot != null && !agentLogRoot.isBlank()) {
      childCommand.add(
          "-Dgimle.log.root=" + Path.of(agentLogRoot).resolve("workers").resolve("child"));
    }
    childCommand.add("-cp");
    childCommand.add(System.getProperty("java.class.path"));
    childCommand.add(ChildFixtureMain.class.getName());
    childCommand.add(String.valueOf(childIgnoresSigterm));
    new ProcessBuilder(childCommand).inheritIO().start();

    try (ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
      while (true) {
        try (Socket accepted = socket.accept()) {
          // Nothing to do with it -- every readiness probe just needs the handshake to succeed.
        }
      }
    }
  }
}
