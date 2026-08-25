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
 *
 * <p>See {@link #setRestricted} for the other honest scope cut: when a {@code NetworkPolicySpec}
 * applies to this service, every new connection is refused outright rather than proxied, since this
 * listener relays opaque bytes for whatever protocol the caller speaks and has no way to verify who
 * that caller is.
 */
final class ServiceListener implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ServiceListener.class);

  private final String serviceName;
  private final ServerSocket serverSocket;
  private final InetSocketAddress boundAddress;
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile List<ServiceEndpoint> endpoints = List.of();
  private volatile boolean restricted;
  private volatile boolean closed;

  ServiceListener(String serviceName, InetAddress clusterIp, int port) throws IOException {
    this(serviceName, new InetSocketAddress(clusterIp, port));
  }

  /**
   * {@code bindAddress} is either a synthesized per-service loopback ClusterIP (the default) or the
   * wildcard address when {@code BifrostProxy} is exposing services off-node -- this listener
   * itself doesn't care which; every behavior below is address-agnostic.
   */
  ServiceListener(String serviceName, InetSocketAddress bindAddress) throws IOException {
    this.serviceName = serviceName;
    this.serverSocket = new ServerSocket();
    serverSocket.bind(bindAddress);
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

  /**
   * Whether {@link BifrostProxy} has determined a {@code NetworkPolicySpec} currently restricts
   * this service's own tenant/deployment -- when {@code true}, {@link #forward} refuses every new
   * connection outright rather than choosing an endpoint, since this loopback proxy has no way to
   * verify a connecting caller's own tenant identity to check against the policy's allow list
   * (unlike {@code FabricServer}, which decodes the fabric wire protocol and can read the caller's
   * wire-carried tenant claim, Bifrost blindly relays opaque bytes for any protocol). Failing
   * closed is the only sound choice given that blind spot: proxying anyway would make Bifrost a
   * silent bypass of a policy the tenant explicitly opted into.
   */
  void setRestricted(boolean restricted) {
    this.restricted = restricted;
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
    if (restricted) {
      log.warn(
          "bifrost declines to proxy service {}: a NetworkPolicySpec restricts its tenant/"
              + "deployment and this loopback proxy cannot verify the caller's own tenant identity"
              + " -- failing closed rather than risk silently bypassing the policy",
          serviceName);
      closeQuietly(inbound);
      return;
    }
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
