package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

  /** A mutable {@code NetworkPolicySource} fake, the {@link InMemoryServiceSource} analogue. */
  private static final class MutableNetworkPolicySource implements NetworkPolicySource {
    private volatile List<NetworkPolicyRule> rules = List.of();

    void set(List<NetworkPolicyRule> newRules) {
      this.rules = newRules;
    }

    @Override
    public List<NetworkPolicyRule> fetchPolicies() {
      return rules;
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
}
