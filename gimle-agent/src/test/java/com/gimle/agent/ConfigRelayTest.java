package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ConfigRelay} driven directly via {@link ConfigRelay#pollOnce()} against a real connected
 * {@link WorkerConnection} pair -- the same deterministic-poll style {@code NetworkPolicyRelayTest}
 * already uses, exercising the real {@code ControlMessageCodec} round trip rather than a mock.
 */
class ConfigRelayTest {

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
    return supervisedInstance(connection, "orders-service");
  }

  private static SupervisedInstance supervisedInstance(
      WorkerConnection connection, String deploymentName) {
    AssignedInstance assigned =
        new AssignedInstance(
            deploymentName, 0, descriptor().id(), "/does/not/matter.jar", Optional.of("acme"));
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor());
    instance.connection = connection;
    return instance;
  }

  /**
   * The next {@code ConfigDelivered} on the wire, stepping over the {@code ConfigKeysRetained}
   * assertion every successful tick also sends -- the delivery-focused tests below care only about
   * which values moved, and the retention assertions have their own tests.
   */
  private static ControlMessage.ConfigDelivered nextDelivery(WorkerConnection connection)
      throws IOException {
    while (true) {
      ControlMessage message = connection.receive().orElseThrow();
      if (message instanceof ControlMessage.ConfigDelivered delivered) {
        return delivered;
      }
    }
  }

  /** The keys named by the next {@code ConfigKeysRetained} frame, in the order they were sent. */
  private static List<String> nextRetainedKeys(WorkerConnection connection) throws IOException {
    while (true) {
      ControlMessage message = connection.receive().orElseThrow();
      if (message instanceof ControlMessage.ConfigKeysRetained retained) {
        return retained.keys();
      }
    }
  }

  @Test
  @Timeout(10)
  void the_first_poll_delivers_every_fetched_value() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    ConfigRelay relay =
        new ConfigRelay(
            instance ->
                List.of(
                    new AgentMain.ConfigValue("db.url", "jdbc:h2:mem:", false),
                    new AgentMain.ConfigValue("db.password", "hunter2", true)),
            Duration.ofMinutes(5),
            supervised);

    relay.pollOnce();

    assertEquals(
        new ControlMessage.ConfigDelivered("db.url", "jdbc:h2:mem:", false), nextDelivery(pair[1]));
    assertEquals(
        new ControlMessage.ConfigDelivered("db.password", "hunter2", true), nextDelivery(pair[1]));
  }

  @Test
  @Timeout(10)
  void an_unchanged_value_is_not_re_delivered_but_a_changed_one_is() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    AtomicReference<String> secretValue = new AtomicReference<>("v1");
    ConfigRelay relay =
        new ConfigRelay(
            instance ->
                List.of(
                    new AgentMain.ConfigValue("stable", "same", false),
                    new AgentMain.ConfigValue("api.key", secretValue.get(), true)),
            Duration.ofMinutes(5),
            supervised);

    relay.pollOnce(); // delivers both
    nextDelivery(pair[1]);
    nextDelivery(pair[1]);

    secretValue.set("v2-rotated");
    relay.pollOnce();

    // Only the rotated value crosses the wire on the second poll -- "stable" stays quiet. Both
    // keys still exist, so both are still named by the retention assertion that follows it.
    assertEquals(
        new ControlMessage.ConfigDelivered("api.key", "v2-rotated", true), nextDelivery(pair[1]));
    assertEquals(List.of("stable", "api.key"), nextRetainedKeys(pair[1]));
    pair[0].close();
    assertNull(pair[1].receive().orElse(null));
  }

  @Test
  @Timeout(10)
  void a_fetch_failure_skips_that_instance_without_failing_the_poll() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    ConfigRelay relay =
        new ConfigRelay(
            instance -> {
              throw new IOException("control plane unreachable");
            },
            Duration.ofMinutes(5),
            supervised);

    relay.pollOnce();

    pair[0].close();
    assertNull(pair[1].receive().orElse(null));
  }

  @Test
  @Timeout(10)
  void an_instance_with_no_connection_yet_is_skipped() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("not-yet-connected#0", supervisedInstance(null));
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    ConfigRelay relay =
        new ConfigRelay(
            instance -> List.of(new AgentMain.ConfigValue("k", "v", false)),
            Duration.ofMinutes(5),
            supervised);

    relay.pollOnce();

    assertEquals(new ControlMessage.ConfigDelivered("k", "v", false), nextDelivery(pair[1]));
  }

  @Test
  @Timeout(10)
  void bookkeeping_for_a_removed_instance_is_dropped_so_a_reincarnation_gets_a_full_delivery()
      throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    ConfigRelay relay =
        new ConfigRelay(
            instance -> List.of(new AgentMain.ConfigValue("k", "v", false)),
            Duration.ofMinutes(5),
            supervised);
    relay.pollOnce();
    nextDelivery(pair[1]);

    // The instance goes away for a tick (its bookkeeping must be dropped), then a replacement
    // appears under the same key: it must receive a full delivery, not inherit the old one's
    // "already sent" state.
    supervised.clear();
    relay.pollOnce();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    relay.pollOnce();

    assertEquals(new ControlMessage.ConfigDelivered("k", "v", false), nextDelivery(pair[1]));
  }

  @Test
  @Timeout(10)
  void every_successful_tick_asserts_the_full_key_set_that_still_exists() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    AtomicReference<List<AgentMain.ConfigValue>> upstream =
        new AtomicReference<>(
            List.of(
                new AgentMain.ConfigValue("db.url", "jdbc:h2:mem:", false),
                new AgentMain.ConfigValue("db.password", "hunter2", true)));
    ConfigRelay relay =
        new ConfigRelay(instance -> upstream.get(), Duration.ofMinutes(5), supervised);

    relay.pollOnce();
    assertEquals(List.of("db.url", "db.password"), nextRetainedKeys(pair[1]));

    // The secret is deleted upstream; the very next assertion no longer names it, which is what
    // makes the worker drop it.
    upstream.set(List.of(new AgentMain.ConfigValue("db.url", "jdbc:h2:mem:", false)));
    relay.pollOnce();
    assertEquals(List.of("db.url"), nextRetainedKeys(pair[1]));
  }

  @Test
  @Timeout(10)
  void deleting_every_key_asserts_an_empty_set_rather_than_sending_nothing() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    AtomicReference<List<AgentMain.ConfigValue>> upstream =
        new AtomicReference<>(List.of(new AgentMain.ConfigValue("only", "value", false)));
    ConfigRelay relay =
        new ConfigRelay(instance -> upstream.get(), Duration.ofMinutes(5), supervised);
    relay.pollOnce();
    nextRetainedKeys(pair[1]);

    upstream.set(List.of());
    relay.pollOnce();

    assertEquals(List.of(), nextRetainedKeys(pair[1]));
  }

  @Test
  @Timeout(10)
  void a_key_deleted_and_re_created_with_its_old_value_is_delivered_again() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    AtomicReference<List<AgentMain.ConfigValue>> upstream =
        new AtomicReference<>(List.of(new AgentMain.ConfigValue("api.key", "same", true)));
    ConfigRelay relay =
        new ConfigRelay(instance -> upstream.get(), Duration.ofMinutes(5), supervised);
    relay.pollOnce();
    nextDelivery(pair[1]);

    upstream.set(List.of());
    relay.pollOnce();
    upstream.set(List.of(new AgentMain.ConfigValue("api.key", "same", true)));
    relay.pollOnce();

    // Byte-identical to the value delivered before the deletion: without forgetting the retracted
    // key, "unchanged since last sent" bookkeeping would suppress this and leave the worker
    // permanently missing a key that does exist.
    assertEquals(
        new ControlMessage.ConfigDelivered("api.key", "same", true), nextDelivery(pair[1]));
  }

  @Test
  @Timeout(10)
  void a_worker_that_missed_every_earlier_tick_still_converges_on_the_current_key_set()
      throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    SupervisedInstance instance = supervisedInstance(null);
    supervised.put("orders-service#0", instance);
    AtomicReference<List<AgentMain.ConfigValue>> upstream =
        new AtomicReference<>(
            List.of(
                new AgentMain.ConfigValue("kept", "v", false),
                new AgentMain.ConfigValue("revoked", "v", true)));
    ConfigRelay relay =
        new ConfigRelay(supervisedInstance -> upstream.get(), Duration.ofMinutes(5), supervised);

    // Ticks while the worker has no channel at all: the creation, the deletion, and everything
    // between are missed outright.
    relay.pollOnce();
    upstream.set(List.of(new AgentMain.ConfigValue("kept", "v", false)));
    relay.pollOnce();
    relay.pollOnce();

    // The worker connects only now, mid-stream, having observed none of the above.
    instance.connection = pair[0];
    relay.pollOnce();

    assertEquals(new ControlMessage.ConfigDelivered("kept", "v", false), nextDelivery(pair[1]));
    assertEquals(List.of("kept"), nextRetainedKeys(pair[1]));
  }

  @Test
  @Timeout(10)
  void a_shared_tier_1_worker_is_asserted_the_union_of_its_instances_keys() throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    // Density-packed onto one worker: both instances hold the same connection, so a per-instance
    // assertion would have each retract the other's keys from the shared worker config map.
    supervised.put("orders-service#0", supervisedInstance(pair[0], "orders-service"));
    supervised.put("billing-service#0", supervisedInstance(pair[0], "billing-service"));
    ConfigRelay relay =
        new ConfigRelay(
            instance ->
                List.of(
                    new AgentMain.ConfigValue(
                        instance.assigned.deploymentName() + ".key", "v", false)),
            Duration.ofMinutes(5),
            supervised);

    relay.pollOnce();

    assertEquals(
        Set.of("orders-service.key", "billing-service.key"), Set.copyOf(nextRetainedKeys(pair[1])));
  }

  @Test
  @Timeout(10)
  void a_tick_whose_fetch_failed_asserts_nothing_rather_than_retracting_live_keys()
      throws Exception {
    WorkerConnection[] pair = connectedPair();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    AtomicReference<Boolean> failing = new AtomicReference<>(false);
    ConfigRelay relay =
        new ConfigRelay(
            instance -> {
              if (failing.get()) {
                throw new IOException("control plane unreachable");
              }
              return List.of(new AgentMain.ConfigValue("k", "v", false));
            },
            Duration.ofMinutes(5),
            supervised);
    relay.pollOnce();
    nextDelivery(pair[1]);
    assertEquals(List.of("k"), nextRetainedKeys(pair[1]));

    failing.set(true);
    relay.pollOnce();
    failing.set(false);
    relay.pollOnce();

    // The failed tick contributed no assertion at all -- the next frame is the one the recovered
    // tick sent, still naming the key that was live throughout.
    assertEquals(List.of("k"), nextRetainedKeys(pair[1]));
  }
}
