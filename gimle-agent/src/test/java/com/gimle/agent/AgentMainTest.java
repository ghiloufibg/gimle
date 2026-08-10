package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * F-01 regression: {@link AgentMain#prepareResourceLimit} must hand the limiter the manifest's
 * resource *limit*, not its request, and {@link AgentMain#buildWorkerCommand} must then carry that
 * limit's {@code -Xmx} into the spawned worker's command line. Both are exercised directly, not
 * through the full {@code startInstance}/process-spawning path, which {@code
 * AgentWorkerIntegrationTest} and {@code ResourceLimitEnforcementTest} already cover with a
 * hand-built command that never goes through either of these call sites.
 */
class AgentMainTest {

  private static final ResourceSpec REQUEST = new ResourceSpec("16Mi", "500m");
  private static final ResourceSpec LIMIT = new ResourceSpec("64Mi", "2000m");

  private static ModuleDescriptor descriptorWithDistinctRequestAndLimit() {
    return new ModuleDescriptor(
        "hello-module",
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        REQUEST,
        LIMIT,
        HealthProbes.NONE,
        Optional.empty());
  }

  @Test
  void prepare_resource_limit_hands_the_limiter_the_descriptors_limit_not_its_request() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();

    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);

    assertEquals(LIMIT.memoryBytes(), handle.limit().memoryBytes());
    assertEquals(LIMIT.cpuMillicores(), handle.limit().cpuMillicores());
  }

  @Test
  void the_spawned_command_carries_the_manifests_limit_not_its_request() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    List<String> command =
        AgentMain.buildWorkerCommand(
            "java",
            List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
            resourceLimiter,
            handle,
            Path.of("gimle-logs", "workers", "hello-deployment#0"),
            "node-1",
            assigned);

    long limitBytes = LIMIT.memoryBytes();
    long requestBytes = REQUEST.memoryBytes();
    assertTrue(
        command.contains("-Xmx" + limitBytes),
        "expected -Xmx derived from the 64Mi limit, not the 16Mi request; command=" + command);
    assertTrue(
        command.stream().noneMatch(arg -> arg.equals("-Xmx" + requestBytes)),
        "command must not carry the request's -Xmx value; command=" + command);
  }

  @Test
  void the_spawned_command_always_carries_exit_on_out_of_memory_error() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    List<String> command =
        AgentMain.buildWorkerCommand(
            "java",
            List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
            resourceLimiter,
            handle,
            Path.of("gimle-logs", "workers", "hello-deployment#0"),
            "node-1",
            assigned);

    // WorkerProcessSupervisor's OOM crash classification (P2-3) depends on this flag being set on
    // every worker, unconditionally -- without it, an OOM exit is indistinguishable from any
    // other unexpected exit code.
    assertTrue(
        command.contains("-XX:+ExitOnOutOfMemoryError"),
        "expected -XX:+ExitOnOutOfMemoryError in the spawned command; command=" + command);
  }

  @Test
  void the_spawned_command_forwards_the_default_deny_cross_tenant_flag() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    String property = "gimle.fabric.defaultDenyCrossTenant";
    String previous = System.getProperty(property);
    try {
      // Absent -> forwarded as "false", not simply omitted -- every worker gets an explicit
      // value rather than silently inheriting whatever WorkerMain's own default happens to be.
      System.clearProperty(property);
      List<String> withoutProperty =
          AgentMain.buildWorkerCommand(
              "java",
              List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
              resourceLimiter,
              handle,
              Path.of("gimle-logs", "workers", "hello-deployment#0"),
              "node-1",
              assigned);
      assertTrue(
          withoutProperty.contains("-Dgimle.fabric.defaultDenyCrossTenant=false"),
          "expected the flag forwarded as false by default; command=" + withoutProperty);

      System.setProperty(property, "true");
      List<String> withProperty =
          AgentMain.buildWorkerCommand(
              "java",
              List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
              resourceLimiter,
              handle,
              Path.of("gimle-logs", "workers", "hello-deployment#0"),
              "node-1",
              assigned);
      assertTrue(
          withProperty.contains("-Dgimle.fabric.defaultDenyCrossTenant=true"),
          "expected the agent's own property value forwarded; command=" + withProperty);
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void the_spawned_command_always_suppresses_the_startup_banner() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    List<String> command =
        AgentMain.buildWorkerCommand(
            "java",
            List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
            resourceLimiter,
            handle,
            Path.of("gimle-logs", "workers", "hello-deployment#0"),
            "node-1",
            assigned);

    // A worker starts once per module instance, not once per node/replica lifecycle -- unlike
    // BannerPrinter's own enabled-by-default posture (see that class's javadoc), every worker
    // this agent spawns gets it explicitly turned off to keep per-instance logs quiet at scale.
    assertTrue(
        command.contains("-Dgimle.banner.enabled=false"),
        "expected the worker's startup banner to be suppressed; command=" + command);
  }

  @Test
  void observation_json_reports_the_instances_real_self_reported_resource_usage() {
    // Regression test: cpuMillicoresUsed/memoryBytesUsed were previously never populated at all,
    // so AutoscaleReconciler's CPU-utilization math always saw zero. SupervisedInstance's
    // supervisor/server fields are irrelevant to observationJson and left null rather than
    // standing up a real process/socket for this test.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";
    instance.cpuMillicoresUsed = 250L;
    instance.memoryBytesUsed = 1_048_576L;

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals(250L, observation.get("cpuMillicoresUsed"));
    assertEquals(1_048_576L, observation.get("memoryBytesUsed"));
  }

  @Test
  void observation_json_reports_the_instances_real_self_reported_request_and_error_rate() {
    // Regression test: requestRatePerSecond/errorRatePerSecond/queueDepth were previously never
    // populated on the agent side either, so every consumer of a heartbeat's InstanceObservation
    // always saw (0, 0, 0) even once the worker started computing real values.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";
    instance.requestRatePerSecond = 12.5;
    instance.errorRatePerSecond = 0.5;
    instance.queueDepth = 3;

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals(12.5, observation.get("requestRatePerSecond"));
    assertEquals(0.5, observation.get("errorRatePerSecond"));
    assertEquals(3, observation.get("queueDepth"));
  }

  // ---- Tier 1 density: findReusableTier1Worker ----

  private static ModuleDescriptor descriptor(String name, IsolationTier tier) {
    return new ModuleDescriptor(
        name,
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        tier,
        REQUEST,
        LIMIT,
        HealthProbes.NONE,
        Optional.empty());
  }

  private static AssignedInstance assignedInstance(
      String deploymentName, ModuleDescriptor descriptor, Optional<String> tenantId) {
    return new AssignedInstance(
        deploymentName, 0, descriptor.id(), "/does/not/matter.jar", tenantId);
  }

  /**
   * A real (but never connected) {@link SocketChannel}-backed {@link WorkerConnection} -- only its
   * object identity matters to {@code findReusableTier1Worker}'s connection-grouping logic, not its
   * ability to actually send/receive.
   */
  private static WorkerConnection fakeConnection() {
    try {
      return new WorkerConnection(SocketChannel.open());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static SupervisedInstance supervisedInstance(
      AssignedInstance assigned, ModuleDescriptor descriptor, WorkerConnection connection) {
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.connection = connection;
    return instance;
  }

  @Test
  void a_tier1_instance_reuses_an_existing_tier1_worker_of_the_same_tenant() {
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.of("acme"));
    SupervisedInstance existing =
        supervisedInstance(existingAssigned, existingDescriptor, sharedConnection);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("provider-deployment#0", existing);

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.of("acme"));

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(newAssigned, newDescriptor, supervised);

    assertTrue(reusable.isPresent());
    assertEquals(existing, reusable.get());
  }

  @Test
  void a_tier2_instance_is_never_packed_into_an_existing_worker() {
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0",
        supervisedInstance(existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor tier2Descriptor = descriptor("dedicated", IsolationTier.TIER_2);
    AssignedInstance tier2Assigned =
        assignedInstance("dedicated-deployment", tier2Descriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(tier2Assigned, tier2Descriptor, supervised);

    assertFalse(reusable.isPresent());
  }

  @Test
  void different_tenant_tier1_instances_are_not_packed_together() {
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.of("acme"));
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0",
        supervisedInstance(existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.of("other-tenant"));

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(newAssigned, newDescriptor, supervised);

    assertFalse(reusable.isPresent());
  }

  @Test
  void both_untenanted_tier1_instances_are_packed_together() {
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0",
        supervisedInstance(existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(newAssigned, newDescriptor, supervised);

    assertTrue(reusable.isPresent());
  }

  @Test
  void a_worker_already_hosting_the_same_module_is_never_reused_for_another_replica() {
    // Two replicas of the same module landing in the same worker would corrupt WorkerRuntime's
    // per-ModuleId keying -- must be excluded even though nothing else here disqualifies it.
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor descriptor = descriptor("greeter", IsolationTier.TIER_1);
    AssignedInstance replicaZero =
        assignedInstance("greeter-deployment", descriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "greeter-deployment#0", supervisedInstance(replicaZero, descriptor, sharedConnection));

    AssignedInstance replicaOne =
        new AssignedInstance(
            "greeter-deployment", 1, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(replicaOne, descriptor, supervised);

    assertFalse(reusable.isPresent());
  }

  @Test
  void a_worker_at_the_density_cap_is_not_reused() {
    WorkerConnection sharedConnection = fakeConnection();
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    // Fill the shared worker up to MAX_TIER1_DENSITY (4) with four distinct modules.
    for (int i = 0; i < 4; i++) {
      ModuleDescriptor occupantDescriptor = descriptor("occupant-" + i, IsolationTier.TIER_1);
      AssignedInstance occupantAssigned =
          assignedInstance("occupant-" + i + "-deployment", occupantDescriptor, Optional.empty());
      supervised.put(
          "occupant-" + i + "-deployment#0",
          supervisedInstance(occupantAssigned, occupantDescriptor, sharedConnection));
    }

    ModuleDescriptor newDescriptor = descriptor("one-too-many", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("one-too-many-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(newAssigned, newDescriptor, supervised);

    assertFalse(reusable.isPresent());
  }

  @Test
  void a_worker_with_no_established_connection_yet_is_never_reused() {
    // Still starting up (server.accept() hasn't returned yet) -- connection is null.
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0", supervisedInstance(existingAssigned, existingDescriptor, null));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(newAssigned, newDescriptor, supervised);

    assertFalse(reusable.isPresent());
  }
}
