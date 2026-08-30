package com.gimle.agent.bifrost;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import com.gimle.core.tenant.NetworkPolicyRule;
import com.gimle.pki.Subjects;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bound listener for a single service: accepts connections on its synthesized ClusterIP and
 * relays each one, byte-for-byte, to one of the service's currently-live endpoints. One virtual
 * thread runs the accept loop; each accepted connection gets two more, one pumping bytes in each
 * direction, so a slow/stalled reader on one side never blocks the other.
 *
 * <p>Endpoint selection: with {@code sessionAffinity} declared on the Service, a consistent hash of
 * the caller's source address over the (stably sorted) endpoint set pins each caller to one backend
 * -- the {@code sessionAffinity: ClientIP} analogue, deliberately traded against locality since a
 * pin that moved whenever the local endpoint set changed wouldn't be a pin at all. Otherwise
 * round-robin, preferring endpoints on this proxy's own node when any are live -- the same
 * locality-first posture the fabric's own same-worker &rarr; same-machine &rarr; remote ladder
 * takes, collapsed to its two rungs a byte-relay can distinguish.
 *
 * <p>See {@link #forward} for what happens when a {@code NetworkPolicySpec} applies to this
 * service: with a TLS context this listener verifies the caller's certificate-carried tenant
 * against the policy's own allow list; without one it can only refuse outright, since a plaintext
 * byte relay has no caller identity to check. That check runs again on every {@link
 * #setApplicableRules} call, not just at accept time -- {@link BifrostProxy} calls it once per poll
 * tick, so a policy change reaches every connection this listener currently has open, not only the
 * next one it accepts. Without this, a long-lived stream (chunked HTTP, a WebSocket, a gRPC call)
 * opened while a caller's tenant was still permitted would keep flowing indefinitely after that
 * tenant is removed from the allow list or a new deny policy is added.
 */
final class ServiceListener implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ServiceListener.class);

  private final String serviceName;
  private final ServerSocket serverSocket;
  private final InetSocketAddress boundAddress;
  private final Optional<String> localNodeId;
  private final boolean tls;
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile List<ServiceEndpoint> endpoints = List.of();
  private volatile boolean sessionAffinity;
  private volatile List<NetworkPolicyRule> applicableRules = List.of();
  private volatile boolean closed;
  private final Set<OpenConnection> openConnections = ConcurrentHashMap.newKeySet();

  ServiceListener(String serviceName, InetAddress clusterIp, int port) throws IOException {
    this(serviceName, new InetSocketAddress(clusterIp, port), Optional.empty(), Optional.empty());
  }

  ServiceListener(String serviceName, InetSocketAddress bindAddress) throws IOException {
    this(serviceName, bindAddress, Optional.empty(), Optional.empty());
  }

  /**
   * {@code bindAddress} is either a synthesized per-service loopback ClusterIP (the default) or the
   * wildcard address when {@code BifrostProxy} is exposing services off-node. With {@code
   * tlsContext} present the listener terminates TLS itself and requires a cluster-CA-signed client
   * certificate on every connection ({@code needClientAuth}) -- what gives {@link #forward} a
   * verified caller tenant to enforce a network policy against.
   */
  ServiceListener(
      String serviceName,
      InetSocketAddress bindAddress,
      Optional<String> localNodeId,
      Optional<SSLContext> tlsContext)
      throws IOException {
    this.serviceName = serviceName;
    this.localNodeId = localNodeId;
    this.tls = tlsContext.isPresent();
    if (tlsContext.isPresent()) {
      SSLServerSocket sslServerSocket =
          (SSLServerSocket) tlsContext.get().getServerSocketFactory().createServerSocket();
      sslServerSocket.setNeedClientAuth(true);
      this.serverSocket = sslServerSocket;
    } else {
      this.serverSocket = new ServerSocket();
    }
    serverSocket.bind(bindAddress);
    this.boundAddress = (InetSocketAddress) serverSocket.getLocalSocketAddress();
    Thread.ofVirtual().name("gimle-bifrost-listener-" + serviceName).start(this::acceptLoop);
  }

  InetSocketAddress boundAddress() {
    return boundAddress;
  }

  /** Replaces the live endpoint set a new connection will select over. */
  void updateEndpoints(List<ServiceEndpoint> newEndpoints) {
    this.endpoints = List.copyOf(newEndpoints);
  }

  /** Whether the Service currently declares ClientIP-style session affinity. */
  void setSessionAffinity(boolean sessionAffinity) {
    this.sessionAffinity = sessionAffinity;
  }

  /**
   * The ingress-restricting {@code NetworkPolicySpec}s {@link BifrostProxy} determined currently
   * apply to this service's own tenant/deployments -- empty means unrestricted. Non-empty makes
   * {@link #forward} enforce them: against the caller's certificate-carried tenant when this
   * listener terminates TLS, by refusing every connection otherwise (a plaintext byte relay has no
   * caller identity to check, and proxying anyway would silently bypass a policy the tenant
   * explicitly opted into).
   */
  void setApplicableRules(List<NetworkPolicyRule> rules) {
    this.applicableRules = List.copyOf(rules);
    enforceCurrentPolicy();
  }

  /**
   * Closes every currently open connection the just-updated rule set no longer permits. Cheap when
   * nothing changed: an empty rule set (the common case) returns immediately without touching a
   * single connection, and a non-empty one only re-runs the same {@link #policyPermits} check
   * {@link #forward} already ran at accept time, against whatever connections are still open.
   * Called from {@link #setApplicableRules} so a connection is re-checked on the same cadence
   * {@link BifrostProxy} already refreshes the rule set on -- no separate timer, no per-connection
   * thread.
   */
  private void enforceCurrentPolicy() {
    List<NetworkPolicyRule> rules = applicableRules;
    if (rules.isEmpty() || openConnections.isEmpty()) {
      return;
    }
    for (OpenConnection connection : openConnections) {
      if (!policyPermits(rules, connection.inbound())) {
        log.info(
            "bifrost closes an open connection to service {}: no longer permitted by the current"
                + " network policy",
            serviceName);
        closeQuietly(connection.inbound());
        closeQuietly(connection.outbound());
      }
    }
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
    List<NetworkPolicyRule> rules = applicableRules;
    if (!rules.isEmpty() && !policyPermits(rules, inbound)) {
      closeQuietly(inbound);
      return;
    }
    List<ServiceEndpoint> current = endpoints;
    if (current.isEmpty()) {
      log.warn("bifrost has no live endpoints for service {}; dropping connection", serviceName);
      closeQuietly(inbound);
      return;
    }
    ServiceEndpoint target = select(current, inbound);
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
    OpenConnection connection = new OpenConnection(inbound, outbound);
    openConnections.add(connection);
    try {
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
    } finally {
      openConnections.remove(connection);
    }
    closeQuietly(inbound);
    closeQuietly(outbound);
  }

  /**
   * One proxied connection's live socket pair, tracked only for as long as {@link #forward}'s pump
   * threads are running -- what lets {@link #enforceCurrentPolicy} find and close it if a policy
   * change revokes its permission mid-stream. Identity is deliberately the default {@code Socket}
   * one (there is never more than one {@code OpenConnection} per accepted socket), not a value
   * comparison.
   */
  private record OpenConnection(Socket inbound, Socket outbound) {}

  /**
   * Whether the applicable policy set permits this connection. Plaintext listeners can never prove
   * who the caller is, so any applicable rule fails the connection closed. A TLS listener completes
   * the handshake, reads the verified client certificate's {@code O=gimle:tenant:<id>} group, and
   * requires every applicable rule's own allow list to permit that tenant -- a caller whose
   * certificate carries no tenant claim is refused the same way an untenanted fabric caller is.
   */
  private boolean policyPermits(List<NetworkPolicyRule> rules, Socket inbound) {
    if (!tls) {
      log.warn(
          "bifrost declines to proxy service {}: a NetworkPolicySpec restricts its tenant/"
              + "deployment and this plaintext listener cannot verify the caller's own tenant"
              + " identity -- failing closed rather than risk silently bypassing the policy",
          serviceName);
      return false;
    }
    Optional<String> callerTenant = callerTenant(inbound);
    if (callerTenant.isEmpty()) {
      log.warn(
          "bifrost refuses a connection to restricted service {}: the caller's certificate"
              + " carries no gimle:tenant:<id> membership group to check the policy against",
          serviceName);
      return false;
    }
    for (NetworkPolicyRule rule : rules) {
      if (!rule.permitsCallerTenant(callerTenant)) {
        log.warn(
            "bifrost refuses a connection to service {}: network policy {} does not permit"
                + " caller tenant {}",
            serviceName,
            rule.name(),
            callerTenant.get());
        return false;
      }
    }
    return true;
  }

  /** The tenant asserted by the connection's verified client certificate, if any. */
  private Optional<String> callerTenant(Socket inbound) {
    if (!(inbound instanceof SSLSocket sslSocket)) {
      return Optional.empty();
    }
    try {
      sslSocket.startHandshake();
      Certificate[] peerCertificates = sslSocket.getSession().getPeerCertificates();
      if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      Principal principal = Subjects.principalFrom(leaf);
      return BuiltinRoles.tenantOf(principal.groups());
    } catch (SSLPeerUnverifiedException e) {
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      log.warn("bifrost TLS handshake failed on service {}: {}", serviceName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Session affinity pins by consistent hash of the caller's address over a stably-sorted view of
   * the endpoint set, so the same caller lands on the same backend across connections (and across
   * polls, as long as that backend stays live). Otherwise round-robin over the same-node subset
   * when one exists, over everything when it doesn't.
   */
  private ServiceEndpoint select(List<ServiceEndpoint> current, Socket inbound) {
    if (sessionAffinity) {
      List<ServiceEndpoint> sorted = new ArrayList<>(current);
      sorted.sort(Comparator.comparing(ServiceEndpoint::host).thenComparing(ServiceEndpoint::port));
      InetAddress caller = inbound.getInetAddress();
      int hash = caller == null ? 0 : caller.getHostAddress().hashCode();
      return sorted.get(Math.floorMod(hash, sorted.size()));
    }
    List<ServiceEndpoint> pool = current;
    if (localNodeId.isPresent()) {
      List<ServiceEndpoint> local =
          current.stream().filter(e -> e.nodeId().equals(localNodeId)).toList();
      if (!local.isEmpty()) {
        pool = local;
      }
    }
    return pool.get(Math.floorMod(cursor.getAndIncrement(), pool.size()));
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
      } catch (IOException | UnsupportedOperationException ignored) {
        // Already closed by the other direction's own cleanup, or an SSLSocket (which does not
        // support half-close) -- nothing left to shut down either way.
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
