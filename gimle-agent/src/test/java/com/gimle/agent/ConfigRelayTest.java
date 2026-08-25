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
        Optional.empty());
  }

  private static SupervisedInstance supervisedInstance(WorkerConnection connection) {
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor().id(), "/does/not/matter.jar", Optional.of("acme"));
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor());
    instance.connection = connection;
    return instance;
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
        new ControlMessage.ConfigDelivered("db.url", "jdbc:h2:mem:", false),
        pair[1].receive().orElseThrow());
    assertEquals(
        new ControlMessage.ConfigDelivered("db.password", "hunter2", true),
        pair[1].receive().orElseThrow());
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
    pair[1].receive().orElseThrow();
    pair[1].receive().orElseThrow();

    secretValue.set("v2-rotated");
    relay.pollOnce();

    // Only the rotated value crosses the wire on the second poll -- "stable" stays quiet.
    assertEquals(
        new ControlMessage.ConfigDelivered("api.key", "v2-rotated", true),
        pair[1].receive().orElseThrow());
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

    assertEquals(
        new ControlMessage.ConfigDelivered("k", "v", false), pair[1].receive().orElseThrow());
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
    pair[1].receive().orElseThrow();

    // The instance goes away for a tick (its bookkeeping must be dropped), then a replacement
    // appears under the same key: it must receive a full delivery, not inherit the old one's
    // "already sent" state.
    supervised.clear();
    relay.pollOnce();
    supervised.put("orders-service#0", supervisedInstance(pair[0]));
    relay.pollOnce();

    assertEquals(
        new ControlMessage.ConfigDelivered("k", "v", false), pair[1].receive().orElseThrow());
  }
}
