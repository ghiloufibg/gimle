package com.gimle.agent.bifrost;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The datagram counterpart of {@link ServiceListener}: one bound UDP socket fronting a single
 * Service, relaying each datagram to one of its currently-live endpoints and each reply back to
 * whichever client the request came from.
 *
 * <p>UDP has no connection to accept, which is the whole difficulty. A TCP relay learns where a
 * reply belongs from the socket it arrived on; here every datagram arrives on the one bound socket,
 * so the listener has to remember the mapping itself. It keeps a <em>session</em> per client
 * address: a dedicated upstream {@link DatagramSocket} connected to the selected endpoint, plus a
 * reader thread that pushes anything coming back out through the bound socket addressed to that
 * client. This is the same shape kube-proxy's own userspace UDP mode used, and the same reason it
 * needed it.
 *
 * <p>A session pins its backend for its lifetime, whether or not the Service declares {@code
 * sessionAffinity}. That is not the affinity feature leaking in -- it falls out of having one
 * upstream socket per client, and there is no alternative that still routes replies correctly.
 * {@code sessionAffinity} still changes behaviour: it decides how a <em>new</em> session picks its
 * backend (consistent hash of the client address, so a client returning after its session expires
 * lands on the same endpoint) rather than round-robin.
 *
 * <p>Sessions are reaped once idle past {@link #SESSION_IDLE_TIMEOUT_NANOS}, swept on the receive
 * path rather than by a timer -- a listener receiving no traffic has nothing to reap, and one
 * receiving traffic sweeps often enough without a thread of its own. Without this, a long-lived
 * listener would accumulate one socket and one thread per client address ever seen.
 *
 * <p>Network policy is enforced the only way a datagram relay can: {@link #setApplicableRules} with
 * a non-empty rule set fails the listener closed, dropping every datagram. A UDP relay has no
 * handshake and no peer certificate, so it can never learn a caller's tenant -- exactly the limit
 * {@link ServiceListener} documents for its own plaintext case, and refusing is the only answer
 * that does not silently bypass a policy the tenant opted into.
 */
final class UdpServiceListener implements ServiceRelay {

  private static final Logger log = LoggerFactory.getLogger(UdpServiceListener.class);

  /** The largest a UDP payload can be, so no datagram is ever truncated by an undersized buffer. */
  private static final int MAX_DATAGRAM_BYTES = 65_507;

  private static final long SESSION_IDLE_TIMEOUT_NANOS = 60_000_000_000L;

  private final String serviceName;
  private final DatagramSocket socket;
  private final InetSocketAddress boundAddress;
  private final Optional<String> localNodeId;
  private final AtomicInteger cursor = new AtomicInteger();
  private final Thread receiveThread;
  private final Map<InetSocketAddress, Session> sessions = new ConcurrentHashMap<>();
  private volatile List<ServiceEndpoint> endpoints = List.of();
  private volatile boolean sessionAffinity;
  private volatile List<NetworkPolicyRule> applicableRules = List.of();
  private volatile boolean closed;

  UdpServiceListener(
      String serviceName, InetSocketAddress bindAddress, Optional<String> localNodeId)
      throws IOException {
    this.serviceName = serviceName;
    this.localNodeId = localNodeId;
    this.socket = bindWithReuse(bindAddress);
    this.boundAddress = new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    this.receiveThread =
        Thread.ofVirtual()
            .name("gimle-bifrost-udp-listener-" + serviceName)
            .start(this::receiveLoop);
  }

  /**
   * {@code new DatagramSocket(bindAddress)} binds with {@code SO_REUSEADDR} off, which is fine for
   * TCP-shaped port sharing but not for UDP: this listener's own bind address is a specific
   * interface (a synthesized loopback ClusterIP, or the real node interface when {@code
   * BifrostProxy} exposes services), and the backing workload it fronts very often binds its own
   * socket to the wildcard address at the identical numeric port -- a completely ordinary listen
   * pattern for a UDP server. Linux's overlap check for {@code SOCK_DGRAM} treats a wildcard bind
   * and a specific-address bind at the same port as colliding unless {@code SO_REUSEADDR} is set on
   * whichever side is already occupying the port as well as the side binding after it, whereas the
   * equivalent TCP check is more permissive -- so the identical shape of Service worked for a TCP
   * listener and failed here with "Address already in use", repeatably, on every single poll tick,
   * even against a workload that would have cooperated if this listener's own socket had ever asked
   * to share. Binding unbound-then-explicit rather than through the one-shot constructor is what
   * makes setting the option before bind possible at all -- {@code DatagramSocket}'s socket-option
   * setters throw once a socket is already bound. {@code SO_REUSEPORT} is set best-effort alongside
   * it: not every platform's {@link DatagramSocket} exposes it. Neither option can force a bind to
   * succeed against a peer socket that itself sets neither -- that peer's own choice, out of this
   * listener's control, is a kernel-level limit no bind call on this side can paper over.
   */
  private static DatagramSocket bindWithReuse(InetSocketAddress bindAddress) throws IOException {
    DatagramSocket socket = new DatagramSocket((SocketAddress) null);
    try {
      socket.setReuseAddress(true);
      try {
        socket.setOption(StandardSocketOptions.SO_REUSEPORT, true);
      } catch (UnsupportedOperationException | IOException ignored) {
        // Not every platform's DatagramSocket supports SO_REUSEPORT; SO_REUSEADDR above already
        // resolves the wildcard/specific-address overlap this listener actually hits.
      }
      socket.bind(bindAddress);
    } catch (SocketException e) {
      socket.close();
      throw e;
    }
    return socket;
  }

  @Override
  public InetSocketAddress boundAddress() {
    return boundAddress;
  }

  @Override
  public void updateEndpoints(List<ServiceEndpoint> newEndpoints) {
    this.endpoints = List.copyOf(newEndpoints);
  }

  @Override
  public void setSessionAffinity(boolean affinity) {
    this.sessionAffinity = affinity;
  }

  /**
   * A non-empty rule set both stops new datagrams being relayed and tears down every session
   * already established, so a policy change reaches traffic already in flight rather than only the
   * next client -- the same reason {@link ServiceListener} re-checks its open connections here.
   */
  @Override
  public void setApplicableRules(List<NetworkPolicyRule> rules) {
    this.applicableRules = List.copyOf(rules);
    if (!rules.isEmpty() && !sessions.isEmpty()) {
      log.info(
          "bifrost drops {} open UDP session(s) for service {}: a network policy now restricts it"
              + " and a datagram relay cannot verify caller tenants",
          sessions.size(),
          serviceName);
      closeAllSessions();
    }
  }

  private void receiveLoop() {
    byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    while (!closed) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (IOException e) {
        if (closed) {
          return;
        }
        log.warn("bifrost UDP receive for service {} failed: {}", serviceName, e.getMessage());
        continue;
      }
      if (closed) {
        return;
      }
      sweepIdleSessions();
      relay(packet);
    }
  }

  private void relay(DatagramPacket packet) {
    if (!applicableRules.isEmpty()) {
      log.warn(
          "bifrost drops a datagram for service {}: a NetworkPolicySpec restricts its tenant/"
              + "deployment and a datagram relay cannot verify the caller's own tenant identity"
              + " -- failing closed rather than risk silently bypassing the policy",
          serviceName);
      return;
    }
    InetSocketAddress client = new InetSocketAddress(packet.getAddress(), packet.getPort());
    Session session = sessions.get(client);
    if (session == null) {
      List<ServiceEndpoint> current = endpoints;
      if (current.isEmpty()) {
        log.warn("bifrost has no live endpoints for service {}; dropping datagram", serviceName);
        return;
      }
      session = openSession(client, select(current, client.getAddress()));
      if (session == null) {
        return;
      }
    }
    session.touch();
    try {
      session
          .upstream()
          .send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength()));
    } catch (IOException e) {
      log.warn(
          "bifrost failed to forward a datagram to endpoint {}:{} for service {}: {}",
          session.target().host(),
          session.target().port(),
          serviceName,
          e.getMessage());
      closeSession(client, session);
    }
  }

  /**
   * Opens one client's upstream socket and its reply reader. {@code computeIfAbsent} rather than a
   * plain put: two datagrams from the same client can be in flight through {@link #relay} at once,
   * and a lost race would leak the socket and thread the loser created.
   */
  private Session openSession(InetSocketAddress client, ServiceEndpoint target) {
    return sessions.computeIfAbsent(
        client,
        unused -> {
          DatagramSocket upstream;
          try {
            upstream = new DatagramSocket();
            upstream.connect(InetAddress.getByName(target.host()), target.port());
          } catch (IOException e) {
            log.warn(
                "bifrost failed to open an upstream socket to {}:{} for service {}: {}",
                target.host(),
                target.port(),
                serviceName,
                e.getMessage());
            return null;
          }
          Session created = new Session(upstream, target);
          // The reader is not held or joined: it exits on its own when the session's upstream
          // socket closes, which is the only way a session ever ends.
          Thread.ofVirtual()
              .name("gimle-bifrost-udp-reply-" + serviceName)
              .start(() -> readReplies(client, created));
          return created;
        });
  }

  /** Pumps one session's replies back to its own client until the session or listener closes. */
  private void readReplies(InetSocketAddress client, Session session) {
    byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    while (!closed && !session.upstream().isClosed()) {
      DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
      try {
        session.upstream().receive(reply);
      } catch (IOException e) {
        return;
      }
      session.touch();
      try {
        socket.send(
            new DatagramPacket(reply.getData(), reply.getOffset(), reply.getLength(), client));
      } catch (IOException e) {
        if (!closed) {
          log.warn(
              "bifrost failed to return a datagram to {} for service {}: {}",
              client,
              serviceName,
              e.getMessage());
        }
        return;
      }
    }
  }

  private void sweepIdleSessions() {
    long now = System.nanoTime();
    sessions.forEach(
        (client, session) -> {
          if (now - session.lastUsedNanos > SESSION_IDLE_TIMEOUT_NANOS) {
            closeSession(client, session);
          }
        });
  }

  private void closeSession(InetSocketAddress client, Session session) {
    if (sessions.remove(client, session)) {
      session.upstream().close();
    }
  }

  private void closeAllSessions() {
    sessions.forEach(this::closeSession);
  }

  /**
   * Same locality-first posture {@link ServiceListener#select} takes, over the same endpoint set --
   * the choice is per session rather than per datagram, since a session's replies can only come
   * back from the backend its upstream socket is connected to.
   */
  private ServiceEndpoint select(List<ServiceEndpoint> current, InetAddress caller) {
    if (sessionAffinity) {
      List<ServiceEndpoint> sorted = new ArrayList<>(current);
      sorted.sort(Comparator.comparing(ServiceEndpoint::host).thenComparing(ServiceEndpoint::port));
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

  @Override
  public void close() {
    closed = true;
    socket.close();
    closeAllSessions();
    try {
      receiveThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One client's relay state. {@code lastUsedNanos} is written from both the receive loop and this
   * session's own reply reader, so it is volatile rather than guarded -- a sweep reading a slightly
   * stale value at worst reaps a session one tick later than it could have.
   */
  private static final class Session {
    private final DatagramSocket upstream;
    private final ServiceEndpoint target;
    private volatile long lastUsedNanos = System.nanoTime();

    Session(DatagramSocket upstream, ServiceEndpoint target) {
      this.upstream = upstream;
      this.target = target;
    }

    DatagramSocket upstream() {
      return upstream;
    }

    ServiceEndpoint target() {
      return target;
    }

    void touch() {
      lastUsedNanos = System.nanoTime();
    }
  }
}
