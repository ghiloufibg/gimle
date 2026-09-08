package com.gimle.hilmir.launch.fixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

/**
 * Binds a listening socket like {@link SocketFixtureMain}, but first spawns a real child OS process
 * running {@link ChildFixtureMain} -- giving hilmir's own descendant-kill tests a genuine process
 * tree (this process's own pid, plus a real child pid beneath it) without needing an actual Gimlé
 * agent/worker pair to exercise the same shape.
 */
public final class ParentWithChildFixtureMain {

  private ParentWithChildFixtureMain() {}

  public static void main(final String[] args) throws IOException {
    final int port = Integer.parseInt(args[0]);
    final boolean childIgnoresSigterm = Boolean.parseBoolean(args[1]);
    new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            ChildFixtureMain.class.getName(),
            String.valueOf(childIgnoresSigterm))
        .inheritIO()
        .start();

    try (ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
      while (true) {
        try (Socket accepted = socket.accept()) {
          // Nothing to do with it -- every readiness probe just needs the handshake to succeed.
        }
      }
    }
  }
}
