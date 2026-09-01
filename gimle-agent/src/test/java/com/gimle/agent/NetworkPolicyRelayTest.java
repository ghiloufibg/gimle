package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link NetworkPolicyRelay} driven directly via {@link NetworkPolicyRelay#pollOnce()} against a
 * real connected {@link WorkerConnection} pair -- no live control plane, no wall-clock polling --
 * the same deterministic-poll style {@code BifrostProxyTest} already uses for its own poller.
 */
class NetworkPolicyRelayTest {

  private static final ResourceSpec RESOURCES = new ResourceSpec("16Mi", "500m");

  private ServerSocketChannel serverChannel;
  private SocketChannel agentSideChannel;
  private SocketChannel workerSideChannel;

  @AfterEach
  void tearDown() throws IOException {
    if (agentSideChannel != null) {
      agentSideChannel.close();
    }
    if (workerSideChannel != null) {
      workerSideChannel.close();
    }
    if (serverChannel != null) {
      serverChannel.close();
    }
  }

  /**
   * A real loopback socket pair: {@code agentSide} is the end {@link NetworkPolicyRelay} sends on
   * (wired into a {@link SupervisedInstance#connection}, exactly like production), {@code
   * workerSide} is the end a test reads from to observe what actually crossed the wire -- avoiding
   * a fake/mock {@link WorkerConnection} that couldn't exercise the real {@link
   * com.gimle.core.protocol.ControlMessageCodec} round trip.
   */
  private WorkerConnection[] connectedPair() throws IOException {
    serverChannel = ServerSocketChannel.open();
    serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
    workerSideChannel = SocketChannel.open(serverChannel.getLocalAddress());
    agentSideChannel = serverChannel.accept();
    return new WorkerConnection[] {
      new WorkerConnection(agentSideChannel), new WorkerConnection(workerSideChannel)
    };
  }

  private static ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        "greeter",
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        RESOURCES,
        RESOURCES,
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  private static SupervisedInstance supervisedInstance(WorkerConnection connection) {
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor().id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor());
    instance.connection = connection;
    return instance;
  }

  private static final class StaticSource implements NetworkPolicySource {
    private final List<NetworkPolicyRule> rules;
    private final Set<String> denyByDefaultTenantIds;

    StaticSource(List<NetworkPolicyRule> rules) {
      this(rules, Set.of());
    }

    StaticSource(List<NetworkPolicyRule> rules, Set<String> denyByDefaultTenantIds) {
      this.rules = rules;
      this.denyByDefaultTenantIds = denyByDefaultTenantIds;
    }

    @Override
    public NetworkPolicySnapshot fetchPolicies() {
      return new NetworkPolicySnapshot(rules, denyByDefaultTenantIds);
    }
  }

  private static final class FailingSource implements NetworkPolicySource {
    @Override
    public NetworkPolicySnapshot fetchPolicies() throws IOException {
      throw new IOException("control plane unreachable");
    }
  }

  @Test
  @Timeout(10)
  void a_poll_relays_the_current_policy_set_to_every_supervised_worker() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    List<NetworkPolicyRule> rules =
        List.of(new NetworkPolicyRule("deny-by-default", "acme", Set.of("partner-tenant")));

    NetworkPolicyRelay relay =
        new NetworkPolicyRelay(new StaticSource(rules), Duration.ofMinutes(5), supervised);
    relay.pollOnce();

    ControlMessage received = pair[1].receive().orElseThrow();
    assertEquals(new ControlMessage.NetworkPoliciesUpdated(rules, Set.of()), received);
  }

  /**
   * A closed tenant with no policies at all is the case a policy list alone cannot express, so the
   * posture has to reach the worker even when there is nothing else to relay.
   */
  @Test
  @Timeout(10)
  void a_poll_relays_the_deny_by_default_tenants_alongside_the_policy_set() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));

    NetworkPolicyRelay relay =
        new NetworkPolicyRelay(
            new StaticSource(List.of(), Set.of("acme")), Duration.ofMinutes(5), supervised);
    relay.pollOnce();

    ControlMessage received = pair[1].receive().orElseThrow();
    assertEquals(new ControlMessage.NetworkPoliciesUpdated(List.of(), Set.of("acme")), received);
  }

  @Test
  @Timeout(10)
  void an_empty_policy_set_still_relays_so_a_worker_clears_a_previously_held_policy()
      throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));

    NetworkPolicyRelay relay =
        new NetworkPolicyRelay(new StaticSource(List.of()), Duration.ofMinutes(5), supervised);
    relay.pollOnce();

    ControlMessage received = pair[1].receive().orElseThrow();
    assertEquals(new ControlMessage.NetworkPoliciesUpdated(List.of(), Set.of()), received);
  }

  @Test
  @Timeout(10)
  void an_instance_with_no_connection_yet_is_skipped_without_failing_the_whole_poll()
      throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    // A freshly-assigned instance whose spawned worker JVM hasn't connected yet -- connection is
    // still null, the same in-flight state SupervisedInstance's own javadoc documents.
    supervised.put("not-yet-connected#0", supervisedInstance(null));
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    List<NetworkPolicyRule> rules =
        List.of(new NetworkPolicyRule("deny-by-default", "acme", Set.of()));

    NetworkPolicyRelay relay =
        new NetworkPolicyRelay(new StaticSource(rules), Duration.ofMinutes(5), supervised);
    relay.pollOnce();

    ControlMessage received = pair[1].receive().orElseThrow();
    assertEquals(new ControlMessage.NetworkPoliciesUpdated(rules, Set.of()), received);
  }

  @Test
  @Timeout(10)
  void a_source_failure_is_swallowed_and_relays_nothing_that_poll() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));

    NetworkPolicyRelay relay =
        new NetworkPolicyRelay(new FailingSource(), Duration.ofMinutes(5), supervised);
    relay.pollOnce();

    pair[0].close();
    assertNull(pair[1].receive().orElse(null));
  }
}
