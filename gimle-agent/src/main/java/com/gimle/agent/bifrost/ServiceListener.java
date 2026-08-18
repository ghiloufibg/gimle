package com.gimle.agent.bifrost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bound loopback listener for a single service: accepts connections on its synthesized
 * ClusterIP and relays each one, byte-for-byte, to one of the service's currently-live endpoints
 * chosen round-robin. One virtual thread runs the accept loop; each accepted connection gets two
 * more, one pumping bytes in each direction, so a slow/stalled reader on one side never blocks the
 * other.
 *
 * <p>Round-robin, not a locality- or load-aware selector: an honest, deliberate scope cut for this
 * component's first cut, not an oversight.
 */
final class ServiceListener implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ServiceListener.class);

  private final String serviceName;
  private final ServerSocket serverSocket;
  private final InetSocketAddress boundAddress;
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile List<ServiceEndpoint> endpoints = List.of();
  private volatile boolean closed;

  ServiceListener(String serviceName, InetAddress clusterIp, int port) throws IOException {
    this.serviceName = serviceName;
    this.serverSocket = new ServerSocket();
    serverSocket.bind(new InetSocketAddress(clusterIp, port));
    this.boundAddress = (InetSocketAddress) serverSocket.getLocalSocketAddress();
    Thread.ofVirtual().name("gimle-bifrost-listener-" + serviceName).start(this::acceptLoop);
  }

  InetSocketAddress boundAddress() {
    return boundAddress;
  }

  /** Replaces the live endpoint set a new connection will round-robin over. */
  void updateEndpoints(List<ServiceEndpoint> newEndpoints) {
    this.endpoints = List.copyOf(newEndpoints);
  }

  private void acceptLoop() {
    while (!closed) {
      Socket inbound;
      try {
        inbound = serverSocket.accept();
      } catch (IOException e) {
        if (closed) {
          return;
        }
        log.warn("bifrost accept loop for service {} failed: {}", serviceName, e.getMessage());
        continue;
      }
      Thread.ofVirtual()
          .name("gimle-bifrost-connection-" + serviceName)
          .start(() -> forward(inbound));
    }
  }

  private void forward(Socket inbound) {
    List<ServiceEndpoint> current = endpoints;
    if (current.isEmpty()) {
      log.warn("bifrost has no live endpoints for service {}; dropping connection", serviceName);
      closeQuietly(inbound);
      return;
    }
    ServiceEndpoint target = current.get(Math.floorMod(cursor.getAndIncrement(), current.size()));
    Socket outbound;
    try {
      outbound = new Socket(target.host(), target.port());
    } catch (IOException e) {
      log.warn(
          "bifrost failed to reach endpoint {}:{} for service {}: {}",
          target.host(),
          target.port(),
          serviceName,
          e.getMessage());
      closeQuietly(inbound);
      return;
    }
    Thread clientToBackend =
        Thread.ofVirtual()
            .name("gimle-bifrost-relay-" + serviceName + "-out")
            .start(() -> pump(inbound, outbound));
    Thread backendToClient =
        Thread.ofVirtual()
            .name("gimle-bifrost-relay-" + serviceName + "-in")
            .start(() -> pump(outbound, inbound));
    joinQuietly(clientToBackend);
    joinQuietly(backendToClient);
    closeQuietly(inbound);
    closeQuietly(outbound);
  }

  /** Copies bytes from {@code from} to {@code to} until EOF, then half-closes {@code to}. */
  private static void pump(Socket from, Socket to) {
    try {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      in.transferTo(out);
    } catch (IOException e) {
      // Normal at the end of a proxied session: the peer closed, or this socket was closed out
      // from under the pump by the other direction finishing first.
    } finally {
      try {
        to.shutdownOutput();
      } catch (IOException ignored) {
        // Already closed by the other direction's own cleanup -- nothing left to shut down.
      }
    }
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Best-effort cleanup; nothing further to do about a failed close.
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      serverSocket.close();
    } catch (IOException e) {
      log.warn("bifrost failed to close listener for service {}: {}", serviceName, e.getMessage());
    }
  }
}
