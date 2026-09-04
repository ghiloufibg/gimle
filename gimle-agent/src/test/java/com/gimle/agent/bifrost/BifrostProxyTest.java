package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link BifrostProxy} driven with an {@link InMemoryServiceSource} against real in-process backend
 * sockets: no live control plane, no wall-clock polling -- every scenario mutates the fake source
 * and then calls {@link BifrostProxy#pollOnce()} directly to observe the reconciliation
 * deterministically.
 */
class BifrostProxyTest {

  private final InMemoryServiceSource source = new InMemoryServiceSource();
  private BifrostProxy proxy;
  private final List<ServerSocket> backends = new ArrayList<>();

  @AfterEach
  void tearDown() throws IOException {
    if (proxy != null) {
      proxy.close();
    }
    for (ServerSocket backend : backends) {
      backend.close();
    }
  }

  /**
   * A minimal backend: accepts connections forever, and for each one writes {@code tag} followed by
   * a newline, then closes -- just enough for a test client to identify which backend actually
   * served a given proxied connection.
   */
  private ServiceEndpoint startTaggedBackend(String tag) throws IOException {
    ServerSocket serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    backends.add(serverSocket);
    Thread.ofVirtual()
        .name("bifrost-test-backend-" + tag)
        .start(
            () -> {
              while (!serverSocket.isClosed()) {
                try (Socket connection = serverSocket.accept()) {
                  connection.getOutputStream().write((tag + "\n").getBytes(StandardCharsets.UTF_8));
                  connection.getOutputStream().flush();
                } catch (IOException e) {
                  return;
                }
              }
            });
    return new ServiceEndpoint("127.0.0.1", serverSocket.getLocalPort());
  }

  private static String readTagFrom(InetSocketAddress clusterAddress) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(clusterAddress, 2000);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        return reader.readLine();
      }
    }
  }

  @Test
  @Timeout(15)
  void round_robin_rotates_across_multiple_endpoints() throws Exception {
    ServiceEndpoint backendA = startTaggedBackend("A");
    ServiceEndpoint backendB = startTaggedBackend("B");
    source.put("orders", 9100, List.of(backendA, backendB));
    proxy = new BifrostProxy(source, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    List<String> served =
        List.of(
            readTagFrom(clusterAddress),
            readTagFrom(clusterAddress),
            readTagFrom(clusterAddress),
            readTagFrom(clusterAddress));

    assertEquals(List.of("A", "B", "A", "B"), served);
  }

  @Test
  @Timeout(15)
  void expose_mode_binds_the_wildcard_address_at_the_service_port() throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    int servicePort = freePort();
    source.put("orders", servicePort, List.of(backend));
    proxy =
        new BifrostProxy(
            source,
            NetworkPolicySnapshot::empty,
            new BifrostSettings(Duration.ofMinutes(5), true, Optional.empty(), Optional.empty()));
    proxy.pollOnce();

    InetSocketAddress bound = proxy.boundAddressFor("orders").orElseThrow();

    assertEquals(servicePort, bound.getPort());
    assertTrue(
        bound.getAddress().isAnyLocalAddress(),
        "expose mode must bind the wildcard address, not a loopback ClusterIP");
    assertEquals(
        "A", readTagFrom(new InetSocketAddress(InetAddress.getLoopbackAddress(), servicePort)));
  }

  /** A port that was free a moment ago -- bound and released so the proxy can claim it. */
  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  @Test
  @Timeout(15)
  void a_service_disappearing_from_the_source_closes_its_listener() throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", 9101, List.of(backend));
    proxy = new BifrostProxy(source, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();
    assertEquals("A", readTagFrom(clusterAddress));

    source.remove("orders");
    proxy.pollOnce();

    assertTrue(proxy.boundAddressFor("orders").isEmpty());
    assertThrows(IOException.class, () -> readTagFrom(clusterAddress));
  }

  @Test
  @Timeout(15)
  void a_new_service_appearing_gets_a_new_listener() throws Exception {
    proxy = new BifrostProxy(source, Duration.ofMinutes(5));
    proxy.pollOnce();
    assertTrue(proxy.boundAddressFor("payments").isEmpty());

    ServiceEndpoint backend = startTaggedBackend("P");
    source.put("payments", 9102, List.of(backend));
    proxy.pollOnce();

    InetSocketAddress clusterAddress = proxy.boundAddressFor("payments").orElseThrow();
    assertEquals("P", readTagFrom(clusterAddress));
  }

  @Test
  @Timeout(15)
  void endpoints_on_the_proxys_own_node_are_preferred_over_remote_ones() throws Exception {
    ServiceEndpoint localBackend = startTaggedBackend("L").withNodeId("node-a");
    ServiceEndpoint remoteBackend = startTaggedBackend("R").withNodeId("node-b");
    source.put("orders", 9107, List.of(remoteBackend, localBackend));
    proxy =
        new BifrostProxy(
            source,
            NetworkPolicySnapshot::empty,
            new BifrostSettings(
                Duration.ofMinutes(5), false, Optional.of("node-a"), Optional.empty()));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    List<String> served =
        List.of(
            readTagFrom(clusterAddress), readTagFrom(clusterAddress), readTagFrom(clusterAddress));

    assertEquals(List.of("L", "L", "L"), served);
  }

  @Test
  @Timeout(15)
  void locality_preference_falls_back_to_remote_endpoints_when_no_local_one_is_live()
      throws Exception {
    ServiceEndpoint remoteBackend = startTaggedBackend("R").withNodeId("node-b");
    source.put("orders", 9108, List.of(remoteBackend));
    proxy =
        new BifrostProxy(
            source,
            NetworkPolicySnapshot::empty,
            new BifrostSettings(
                Duration.ofMinutes(5), false, Optional.of("node-a"), Optional.empty()));
    proxy.pollOnce();

    assertEquals("R", readTagFrom(proxy.boundAddressFor("orders").orElseThrow()));
  }

  @Test
  @Timeout(15)
  void session_affinity_pins_a_caller_to_one_backend_across_connections() throws Exception {
    ServiceEndpoint backendA = startTaggedBackend("A");
    ServiceEndpoint backendB = startTaggedBackend("B");
    source.put("orders", Optional.empty(), Set.of(), 9109, true, List.of(backendA, backendB));
    proxy = new BifrostProxy(source, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    String first = readTagFrom(clusterAddress);
    String second = readTagFrom(clusterAddress);
    String third = readTagFrom(clusterAddress);

    // Round-robin would alternate; ClientIP-style affinity keeps one loopback caller pinned.
    assertEquals(first, second);
    assertEquals(first, third);
  }

  /** A mutable {@code NetworkPolicySource} fake, the {@link InMemoryServiceSource} analogue. */
  private static final class MutableNetworkPolicySource implements NetworkPolicySource {
    private volatile List<NetworkPolicyRule> rules = List.of();
    private volatile Set<String> denyByDefaultTenantIds = Set.of();

    void set(List<NetworkPolicyRule> newRules) {
      this.rules = newRules;
    }

    void setDenyByDefaultTenantIds(Set<String> newTenantIds) {
      this.denyByDefaultTenantIds = newTenantIds;
    }

    @Override
    public NetworkPolicySnapshot fetchPolicies() {
      return new NetworkPolicySnapshot(rules, denyByDefaultTenantIds);
    }
  }

  @Test
  @Timeout(15)
  void a_tenant_wide_network_policy_makes_bifrost_refuse_to_proxy_the_restricted_tenants_service()
      throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9103, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(List.of(new NetworkPolicyRule("deny-by-default", "acme", Set.of())));
    proxy = new BifrostProxy(source, policies, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    // The listener is still bound (a caller can still connect), but every connection is refused
    // outright -- proxying would either read garbage from an unstarted response or, worse, block
    // waiting for one, neither of which is "the connection was rejected." The pumped-EOF shape
    // this produces is the same one a_service_disappearing_from_the_source_closes_its_listener
    // already asserts for "no backend to proxy to."
    try (Socket socket = new Socket()) {
      socket.connect(clusterAddress, 2000);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        assertEquals(null, reader.readLine());
      }
    }
  }

  @Test
  @Timeout(15)
  void a_deployment_scoped_network_policy_only_restricts_a_service_it_actually_names()
      throws Exception {
    ServiceEndpoint restrictedBackend = startTaggedBackend("R");
    ServiceEndpoint unrestrictedBackend = startTaggedBackend("U");
    source.put(
        "orders", Optional.of("acme"), Set.of("orders-service"), 9104, List.of(restrictedBackend));
    source.put(
        "payments",
        Optional.of("acme"),
        Set.of("payments-service"),
        9105,
        List.of(unrestrictedBackend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(
        List.of(
            new NetworkPolicyRule(
                "deny-by-default", "acme", Optional.of(Set.of("orders-service")), Set.of())));
    proxy = new BifrostProxy(source, policies, Duration.ofMinutes(5));
    proxy.pollOnce();

    InetSocketAddress restrictedAddress = proxy.boundAddressFor("orders").orElseThrow();
    InetSocketAddress unrestrictedAddress = proxy.boundAddressFor("payments").orElseThrow();
    try (Socket socket = new Socket()) {
      socket.connect(restrictedAddress, 2000);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        assertEquals(null, reader.readLine());
      }
    }
    assertEquals("U", readTagFrom(unrestrictedAddress));
  }

  @Test
  @Timeout(15)
  void
      a_network_policy_lifted_on_a_later_poll_lets_bifrost_resume_proxying_the_now_unrestricted_service()
          throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9106, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(List.of(new NetworkPolicyRule("deny-by-default", "acme", Set.of())));
    proxy = new BifrostProxy(source, policies, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    policies.set(List.of());
    proxy.pollOnce();

    assertEquals("A", readTagFrom(clusterAddress));
  }

  /**
   * A closed tenant with no policies yet written behaves here exactly as an explicit deny-all
   * policy would: this proxy relays opaque bytes for whatever protocol the caller speaks, so it has
   * no caller identity to check an allow list against and refuses rather than silently carrying
   * traffic the tenant asked to be closed to.
   */
  @Test
  @Timeout(15)
  void a_deny_by_default_tenant_with_no_policies_makes_bifrost_refuse_to_proxy_its_service()
      throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9109, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.setDenyByDefaultTenantIds(Set.of("acme"));
    proxy = new BifrostProxy(source, policies, Duration.ofMinutes(5));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    try (Socket socket = new Socket()) {
      socket.connect(clusterAddress, 2000);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        assertEquals(null, reader.readLine());
      }
    }
  }

  @Test
  @Timeout(15)
  void an_open_tenant_with_no_policies_is_proxied_normally() throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9110, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.setDenyByDefaultTenantIds(Set.of("globex"));
    proxy = new BifrostProxy(source, policies, Duration.ofMinutes(5));
    proxy.pollOnce();

    assertEquals("A", readTagFrom(proxy.boundAddressFor("orders").orElseThrow()));
  }

  /**
   * M63: a UDP Service whose own declared {@code port} numerically coincides with a socket a
   * co-located workload already has bound wildcard -- an entirely ordinary way for a UDP server to
   * listen, and one that opts into {@code SO_REUSEADDR} the way a well-behaved server sharing a box
   * with other listeners should. Before the fix this failed to bind, repeatably, with "Address
   * already in use" regardless of the workload's own socket options, since the old, always-reuse-
   * off {@code new DatagramSocket(bindAddress)} could never share a wildcard/specific pair even
   * when the other side cooperated.
   */
  @Test
  @Timeout(15)
  void a_udp_service_binds_despite_a_wildcard_socket_already_holding_its_port() throws Exception {
    DatagramSocket wildcardWorkload = new DatagramSocket((SocketAddress) null);
    wildcardWorkload.setReuseAddress(true);
    wildcardWorkload.bind(new InetSocketAddress((InetAddress) null, 0));
    try {
      int collidingPort = wildcardWorkload.getLocalPort();
      DatagramSocket backendSocket = new DatagramSocket(0);
      try {
        source.putUdp(
            "dns",
            collidingPort,
            List.of(
                new ServiceEndpoint(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    backendSocket.getLocalPort())));
        proxy = new BifrostProxy(source, Duration.ofMinutes(5));
        proxy.pollOnce();

        InetSocketAddress clusterAddress = proxy.boundAddressFor("dns").orElseThrow();
        DatagramSocket client = new DatagramSocket();
        try {
          client.setSoTimeout(3_000);
          byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
          client.send(new DatagramPacket(payload, payload.length, clusterAddress));
          byte[] buffer = new byte[64];
          DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
          backendSocket.receive(reply);
          assertEquals(
              "hello",
              new String(
                  reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8));
        } finally {
          client.close();
        }
      } finally {
        backendSocket.close();
      }
    } finally {
      wildcardWorkload.close();
    }
  }

  /**
   * M64 sub-bug 1: with {@code exposeOnAllInterfaces} on, a Service whose backing instance is
   * co-located on this proxy's own node at the identical port must not have its wildcard bind
   * contested by this proxy -- the workload's own listener already serves that port directly, and a
   * race between the two starves whichever one loses, forever.
   */
  @Test
  @Timeout(15)
  void expose_mode_leaves_a_co_located_same_port_service_to_its_own_instance() throws Exception {
    int servicePort = freePort();
    // Stands in for the real workload's own listening socket: bound at the exact address and port
    // BifrostProxy's wildcard bind would otherwise contest.
    ServerSocket coLocatedWorkload = new ServerSocket(servicePort);
    try {
      Thread.ofVirtual()
          .start(
              () -> {
                while (!coLocatedWorkload.isClosed()) {
                  try (Socket connection = coLocatedWorkload.accept()) {
                    connection
                        .getOutputStream()
                        .write("workload\n".getBytes(StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    return;
                  }
                }
              });
      ServiceEndpoint colocated =
          new ServiceEndpoint(InetAddress.getLoopbackAddress().getHostAddress(), servicePort)
              .withNodeId("this-node");
      source.put("echo", servicePort, List.of(colocated));
      proxy =
          new BifrostProxy(
              source,
              NetworkPolicySnapshot::empty,
              new BifrostSettings(
                  Duration.ofMinutes(5), true, Optional.of("this-node"), Optional.empty()));
      proxy.pollOnce();

      assertTrue(
          proxy.boundAddressFor("echo").isEmpty(),
          "bifrost must not bind a wildcard listener over its own co-located instance's port");
      assertEquals(
          "workload",
          readTagFrom(new InetSocketAddress(InetAddress.getLoopbackAddress(), servicePort)));
    } finally {
      coLocatedWorkload.close();
    }
  }
}
