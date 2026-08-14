package com.gimle.holmgang.loki;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One interposed TCP link: listens on its own loopback port and relays byte-for-byte to the real
 * upstream, one virtual thread per direction per connection. {@link #cut()} severs the link --
 * every live connection is closed and new ones are refused on accept -- and {@link #heal()}
 * restores it. First-party and in-JVM deliberately: no external fault-injection binary, matching
 * the platform's own no-non-Java-runtime stance even in test tooling.
 */
final class LokiProxy implements AutoCloseable {

  private final ServerSocket server;
  private final String upstreamHost;
  private final int upstreamPort;
  private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
  private volatile boolean cut;
  private volatile boolean closed;

  static LokiProxy start(final String upstreamHost, final int upstreamPort) {
    return new LokiProxy(upstreamHost, upstreamPort);
  }

  private LokiProxy(final String upstreamHost, final int upstreamPort) {
    this.upstreamHost = upstreamHost;
    this.upstreamPort = upstreamPort;
    try {
      this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    } catch (final IOException e) {
      throw new HolmgangException(
          "failed binding a proxy port for " + upstreamHost + ":" + upstreamPort, e);
    }
    Thread.ofVirtual()
        .name("loki-accept-" + upstreamHost + ":" + upstreamPort)
        .start(this::acceptLoop);
  }

  int port() {
    return server.getLocalPort();
  }

  void cut() {
    cut = true;
    closeAllLive();
  }

  void heal() {
    cut = false;
  }

  @Override
  public void close() {
    closed = true;
    try {
      server.close();
    } catch (final IOException e) {
      // The accept loop is exiting either way; nothing actionable remains.
    }
    closeAllLive();
  }

  private void acceptLoop() {
    while (!closed) {
      final Socket downstream;
      try {
        downstream = server.accept();
      } catch (final IOException e) {
        return;
      }
      if (cut) {
        closeQuietly(downstream);
        continue;
      }
      Thread.ofVirtual().start(() -> relay(downstream));
    }
  }

  private void relay(final Socket downstream) {
    final Socket upstream;
    try {
      upstream = new Socket(upstreamHost, upstreamPort);
    } catch (final IOException e) {
      closeQuietly(downstream);
      return;
    }
    liveSockets.add(downstream);
    liveSockets.add(upstream);
    Thread.ofVirtual().start(() -> pump(downstream, upstream));
    pump(upstream, downstream);
  }

  private void pump(final Socket from, final Socket to) {
    try {
      from.getInputStream().transferTo(to.getOutputStream());
    } catch (final IOException e) {
      // A cut, a closed peer, or plain EOF -- all end the relay the same way.
    } finally {
      liveSockets.remove(from);
      liveSockets.remove(to);
      closeQuietly(from);
      closeQuietly(to);
    }
  }

  private void closeAllLive() {
    for (final Socket socket : liveSockets) {
      closeQuietly(socket);
    }
    liveSockets.clear();
  }

  private static void closeQuietly(final Socket socket) {
    try {
      socket.close();
    } catch (final IOException e) {
      // Already closing everything; a second failure carries no information.
    }
  }
}
