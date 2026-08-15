package com.gimle.hilmir.launch.fixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A minimal standalone process for hilmir's own launcher tests: binds a listening socket on the
 * given port and actually accepts (and immediately closes) every connection, so {@code
 * ReadinessPoller}/{@code MachineLauncher} can be exercised against a real, killable OS process
 * without needing an actual Gimlé component (a store, a control plane, ...) to spawn. Accepting
 * matters, not just binding: an unaccepted connection sits in the kernel's own accept queue, and a
 * repeated readiness probe against a small backlog can otherwise report the port closed once that
 * queue fills, even though the process is very much alive.
 */
public final class SocketFixtureMain {

  private SocketFixtureMain() {}

  public static void main(final String[] args) throws IOException {
    final int port = Integer.parseInt(args[0]);
    try (ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
      while (true) {
        try (Socket accepted = socket.accept()) {
          // Nothing to do with it -- every readiness probe just needs the handshake to succeed.
        }
      }
    }
  }
}
