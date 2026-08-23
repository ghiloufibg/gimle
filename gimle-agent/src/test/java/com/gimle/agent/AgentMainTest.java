package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.observability.MuninnShipper;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression: {@link AgentMain#prepareResourceLimit} must hand the limiter the manifest's resource
 * *limit*, not its request, and {@link AgentMain#buildWorkerCommand} must then carry that limit's
 * {@code -Xmx} into the spawned worker's command line. Both are exercised directly, not through the
 * full {@code startInstance}/process-spawning path, which {@code AgentWorkerIntegrationTest} and
 * {@code ResourceLimitEnforcementTest} already cover with a hand-built command that never goes
 * through either of these call sites.
 */
class AgentMainTest {

  private static final ResourceSpec REQUEST = new ResourceSpec("16Mi", "500m");
  private static final ResourceSpec LIMIT = new ResourceSpec("64Mi", "2000m");

  /**
   * The eight-arg {@link AgentMain#buildWorkerCommand} call every test below needs, with only the
   * bits that actually vary per test (the limiter, the prepared handle, the assigned instance, and
   * occasionally the node id) exposed as parameters. {@code aotCachePath} is always {@code
   * Optional.empty()} here -- Sleipnir's own flag-insertion behavior is covered by a dedicated test
   * below rather than threaded through every other test in this file.
   */
  private static List<String> buildDefaultWorkerCommand(
      PortableJvmFlagsResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      AssignedInstance assigned,
      String nodeId) {
    return AgentMain.buildWorkerCommand(
        "java",
        List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
        resourceLimiter,
        handle,
        Path.of("gimle-logs", "workers", "hello-deployment#0"),
        nodeId,
        assigned,
        Optional.empty());
  }

  private static List<String> buildDefaultWorkerCommand(
      PortableJvmFlagsResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      AssignedInstance assigned) {
    return buildDefaultWorkerCommand(resourceLimiter, handle, assigned, "node-1");
  }

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
        Optional.empty(),
        Optional.empty(),
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

    List<String> command = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);

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

    List<String> command = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);

    // WorkerProcessSupervisor's OOM crash classification depends on this flag being set on every
    // worker, unconditionally -- without it, an OOM exit is indistinguishable from any
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
      List<String> withoutProperty = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);
      assertTrue(
          withoutProperty.contains("-Dgimle.fabric.defaultDenyCrossTenant=false"),
          "expected the flag forwarded as false by default; command=" + withoutProperty);

      System.setProperty(property, "true");
      List<String> withProperty = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);
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

    List<String> command = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);

    // A worker starts once per module instance, not once per node/replica lifecycle -- unlike
    // GimleBanner's own enabled-by-default posture (see that class's javadoc), every worker
    // this agent spawns gets it explicitly turned off to keep per-instance logs quiet at scale.
    assertTrue(
        command.contains("-Dgimle.banner.enabled=false"),
        "expected the worker's startup banner to be suppressed; command=" + command);
  }

  @Test
  void the_spawned_command_always_forces_json_console_logging() {
    // ConsoleLogEncoder now defaults to colored text, which would break
    // WorkerProcessSupervisor's stdout JSON-sniffing if left on a worker's own piped stdout --
    // this flag is what keeps that sniffing correct by construction rather than by guesswork.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    List<String> command = buildDefaultWorkerCommand(resourceLimiter, handle, assigned, "node-a");

    assertTrue(
        command.contains("-Dgimle.log.console=json"),
        "expected the worker's console output to be forced to JSON; command=" + command);
  }

  @Test
  void a_present_aot_cache_path_inserts_sleipnir_flags_immediately_before_the_command_tail() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "hello-deployment#0", descriptor);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    Path cachePath = Path.of("gimle-data", "aot-cache", "worker-abc123.aot");

    List<String> command =
        AgentMain.buildWorkerCommand(
            "java",
            List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
            resourceLimiter,
            handle,
            Path.of("gimle-logs", "workers", "hello-deployment#0"),
            "node-1",
            assigned,
            Optional.of(cachePath));

    int cacheFlagIndex = command.indexOf("-XX:AOTCache=" + cachePath);
    assertTrue(cacheFlagIndex >= 0, "expected -XX:AOTCache=<path>; command=" + command);
    assertEquals(
        "-XX:AOTMode=auto",
        command.get(cacheFlagIndex + 1),
        "expected -XX:AOTMode=auto right after -XX:AOTCache=; command=" + command);
    assertEquals(
        "-Xlog:aot=warning",
        command.get(cacheFlagIndex + 2),
        "expected -Xlog:aot=warning right after -XX:AOTMode=auto; command=" + command);
    assertEquals(
        "-cp",
        command.get(cacheFlagIndex + 3),
        "expected the AOT flags immediately before commandTail's own -cp; command=" + command);
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

  @Test
  void observation_json_reports_the_instances_self_reported_ports() {
    // Regression coverage for the module-hosted analog of a vessel's own allocatedPorts: a
    // module's ModuleContext#reportPort call reaches this instance's ports field via the worker's
    // own MetricsReport, and observationJson must fold it into the heartbeat the exact same way
    // vesselObservationJson already does for a Vessel -- this is what lets
    // ServiceEndpointResolver#solePort resolve a live endpoint for an ordinary module deployment,
    // not just a Vessel workload.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "web-ui", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";
    instance.ports = Map.of("HTTP_PORT", 8080);

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals(Map.of("HTTP_PORT", 8080), observation.get("ports"));
  }

  @Test
  void observation_json_reports_no_ports_for_an_instance_that_never_reported_any() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "web-ui", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals(Map.of(), observation.get("ports"));
  }

  @Test
  void observation_json_reports_a_completed_job_run_as_alive_but_not_ready() {
    // Regression test locking in observationJson's own documented reasoning: alive is an exclusion
    // check ("not FAILED"), not an inclusion list, so a COMPLETED job run already reports
    // alive=true without observationJson needing a COMPLETED-specific branch -- a successfully
    // finished Job is not a crash HealthReconciler should reschedule. It is,
    // however, not "ready" (ready is strictly ACTIVE-only): a completed run isn't serving traffic.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "nightly-cleanup", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "COMPLETED";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals("COMPLETED", observation.get("lifecycleState"));
    assertEquals(true, observation.get("alive"));
    assertEquals(false, observation.get("ready"));
  }

  @Test
  void observation_json_reports_a_failed_instance_as_not_alive() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "nightly-cleanup", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "FAILED";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals(false, observation.get("alive"));
    assertEquals(false, observation.get("ready"));
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
        Optional.empty(),
        Optional.empty(),
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

  // ---- requiresReplacement: a rolling update's moduleId/artifactPath change at a fixed key ----

  @Test
  void a_module_id_change_at_the_same_key_requires_replacement() {
    ModuleDescriptor v1 = descriptor("provider", IsolationTier.TIER_2);
    AssignedInstance assignedV1 = assignedInstance("provider-deployment", v1, Optional.empty());
    SupervisedInstance existing = supervisedInstance(assignedV1, v1, null);

    ModuleDescriptor v2 =
        new ModuleDescriptor(
            "provider",
            Version.parse("2.0.0"),
            List.of(),
            List.of(),
            IsolationTier.TIER_2,
            REQUEST,
            LIMIT,
            HealthProbes.NONE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    AssignedInstance assignedV2 =
        new AssignedInstance(
            "provider-deployment", 0, v2.id(), "/does/not/matter.jar", Optional.empty());

    assertTrue(AgentMain.requiresReplacement(assignedV2, existing));
  }

  @Test
  void an_artifact_path_change_with_the_same_module_id_requires_replacement() {
    ModuleDescriptor v1 = descriptor("provider", IsolationTier.TIER_2);
    AssignedInstance assignedOriginal =
        assignedInstance("provider-deployment", v1, Optional.empty());
    SupervisedInstance existing = supervisedInstance(assignedOriginal, v1, null);

    AssignedInstance assignedRepublished =
        new AssignedInstance(
            "provider-deployment", 0, v1.id(), "/a/different/path.jar", Optional.empty());

    assertTrue(AgentMain.requiresReplacement(assignedRepublished, existing));
  }

  @Test
  void an_unchanged_assignment_at_the_same_key_never_requires_replacement() {
    ModuleDescriptor v1 = descriptor("provider", IsolationTier.TIER_2);
    AssignedInstance assigned = assignedInstance("provider-deployment", v1, Optional.empty());
    SupervisedInstance existing = supervisedInstance(assigned, v1, null);

    // A fresh AssignedInstance with identical field values, not the same object -- this is what a
    // real re-fetch of unchanged desired state actually looks like.
    AssignedInstance sameAssignmentAgain =
        new AssignedInstance(
            "provider-deployment", 0, v1.id(), "/does/not/matter.jar", Optional.empty());

    assertFalse(AgentMain.requiresReplacement(sameAssignmentAgain, existing));
  }

  // ---- findRenameSource / renameInPlace: surge promotion retargeting an already-running worker
  // instance in place, no restart -- see DeploymentReconciler#handleSurge's own promotion step ----

  private static AssignedInstance renamedAssignment(
      String deploymentName, int instanceIndex, ModuleDescriptor descriptor, int renamedFrom) {
    return new AssignedInstance(
        deploymentName,
        instanceIndex,
        descriptor.id(),
        "/does/not/matter.jar",
        Optional.empty(),
        OptionalInt.of(renamedFrom));
  }

  @Test
  void find_rename_source_finds_the_already_supervised_instance_at_the_hinted_index() {
    ModuleDescriptor v2 = descriptor("orders", IsolationTier.TIER_2);
    AssignedInstance surgeAssigned = assignedInstance("orders-service", v2, Optional.empty());
    SupervisedInstance surgeInstance = supervisedInstance(surgeAssigned, v2, null);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders-service#5", surgeInstance);

    AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);

    Optional<SupervisedInstance> source = AgentMain.findRenameSource(renamed, supervised);

    assertTrue(source.isPresent());
    assertEquals(surgeInstance, source.get());
  }

  @Test
  void find_rename_source_is_empty_without_a_rename_hint() {
    ModuleDescriptor v2 = descriptor("orders", IsolationTier.TIER_2);
    AssignedInstance freshlyPlaced = assignedInstance("orders-service", v2, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();

    assertFalse(AgentMain.findRenameSource(freshlyPlaced, supervised).isPresent());
  }

  @Test
  void find_rename_source_falls_back_when_the_hinted_source_key_is_not_supervised() {
    ModuleDescriptor v2 = descriptor("orders", IsolationTier.TIER_2);
    AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();

    // No "orders-service#5" entry at all -- a genuine race (already renamed on a previous poll,
    // or the source is simply gone) -- the safe fallback is "nothing to rename from", never a
    // thrown exception, so the caller's ordinary start path can proceed unaffected.
    assertFalse(AgentMain.findRenameSource(renamed, supervised).isPresent());
  }

  @Test
  void find_rename_source_falls_back_when_the_source_runs_a_different_module() {
    ModuleDescriptor v1 = descriptor("orders", IsolationTier.TIER_2);
    ModuleDescriptor v2 =
        new ModuleDescriptor(
            "orders",
            Version.parse("2.0.0"),
            List.of(),
            List.of(),
            IsolationTier.TIER_2,
            REQUEST,
            LIMIT,
            HealthProbes.NONE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    AssignedInstance staleAssigned = assignedInstance("orders-service", v1, Optional.empty());
    SupervisedInstance staleInstance = supervisedInstance(staleAssigned, v1, null);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders-service#5", staleInstance);

    // The hint points at index 5, but whatever is actually running there is still v1 -- not a
    // healthy surge instance proven on v2, so this must not be mistaken for a legitimate rename.
    AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);

    assertFalse(AgentMain.findRenameSource(renamed, supervised).isPresent());
  }

  @Test
  void rename_in_place_rekeys_supervised_and_shippers_and_updates_the_assigned_identity() {
    ModuleDescriptor v2 = descriptor("orders", IsolationTier.TIER_2);
    // Index 5, matching the "orders-service#5" key below -- assignedInstance()'s own helper
    // hardcodes index 0, which is fine for requiresReplacement-only tests (moduleId/artifactPath
    // only) but not here, since renameInPlace derives its own "old key" from this exact field.
    AssignedInstance surgeAssigned =
        new AssignedInstance(
            "orders-service", 5, v2.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = supervisedInstance(surgeAssigned, v2, null);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders-service#5", instance);
    Map<String, List<MuninnShipper>> instanceShippers = new LinkedHashMap<>();
    instanceShippers.put("orders-service#5", List.of());
    CapacityTracker capacityTracker = new CapacityTracker(1_000_000_000L, 4000L);
    capacityTracker.tryAssign("orders-service#5", v2.resourceRequest());

    AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);
    AgentMain.renameInPlace(
        "orders-service#1", renamed, instance, supervised, instanceShippers, capacityTracker);

    assertFalse(supervised.containsKey("orders-service#5"));
    assertEquals(instance, supervised.get("orders-service#1"));
    assertEquals(renamed, instance.assigned);
    assertFalse(instanceShippers.containsKey("orders-service#5"));
    assertTrue(instanceShippers.containsKey("orders-service#1"));
    // The capacity reservation must have moved with the key, not stayed leaked under the old one
    // or been dropped -- see CapacityTracker#rekey's own dedicated test for the full behavior.
    assertTrue(capacityTracker.tryAssign("orders-service#5", v2.resourceRequest()));
    assertThrows(
        IllegalStateException.class,
        () -> capacityTracker.tryAssign("orders-service#1", v2.resourceRequest()));
  }

  @Test
  void rename_in_place_notifies_the_connected_worker_of_its_new_identity() throws Exception {
    ModuleDescriptor v2 = descriptor("orders", IsolationTier.TIER_2);
    AssignedInstance surgeAssigned =
        new AssignedInstance(
            "orders-service", 5, v2.id(), "/does/not/matter.jar", Optional.empty());

    Path socketPath = Files.createTempDirectory("gimle-rename-notify").resolve("worker.sock");
    try {
      try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        server.bind(UnixDomainSocketAddress.of(socketPath));
        try (SocketChannel workerRaw = SocketChannel.open(StandardProtocolFamily.UNIX)) {
          workerRaw.connect(UnixDomainSocketAddress.of(socketPath));
          try (SocketChannel agentRaw = server.accept()) {
            WorkerConnection workerSide = new WorkerConnection(workerRaw);
            WorkerConnection agentSide = new WorkerConnection(agentRaw);
            SupervisedInstance instance = supervisedInstance(surgeAssigned, v2, agentSide);
            Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
            supervised.put("orders-service#5", instance);
            Map<String, List<MuninnShipper>> instanceShippers = new LinkedHashMap<>();

            AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);
            AgentMain.renameInPlace(
                "orders-service#1",
                renamed,
                instance,
                supervised,
                instanceShippers,
                new CapacityTracker(1_000_000_000L, 4000L));

            Optional<ControlMessage> received = workerSide.receive();
            assertTrue(received.isPresent());
            assertTrue(received.get() instanceof ControlMessage.RenameInstance);
            ControlMessage.RenameInstance renameMessage =
                (ControlMessage.RenameInstance) received.get();
            assertEquals(v2.id(), renameMessage.id());
            assertEquals("orders-service", renameMessage.deploymentName());
            assertEquals(1, renameMessage.instanceIndex());
          }
        }
      }
    } finally {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(socketPath.getParent());
    }
  }

  // ---- deliverConfig: a tenant instance must not get stuck when Fafnir is unreachable ----

  @Test
  @Timeout(15)
  void deliver_config_degrades_gracefully_when_fafnir_is_unreachable() throws Exception {
    // Real HTTP server backing baseUrl's own /config/{tenantId}, so this test isolates the
    // Fafnir-unreachable path alone rather than also exercising fetchConfigForTenant's own
    // already-covered RuntimeException fallback.
    HttpServer configServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    configServer.createContext(
        "/config/acme",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    configServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    configServer.start();
    try {
      URI baseUrl = URI.create("http://127.0.0.1:" + configServer.getAddress().getPort());

      // Bound then immediately closed, so connecting to it fails fast with a real
      // ConnectException instead of hanging on a real remote host's connect timeout.
      URI fafnirBaseUrl;
      try (ServerSocket unusedFafnirPort = new ServerSocket(0)) {
        fafnirBaseUrl = URI.create("http://127.0.0.1:" + unusedFafnirPort.getLocalPort());
      }

      ModuleDescriptor descriptor = descriptor("provider", IsolationTier.TIER_2);
      AssignedInstance assigned =
          assignedInstance("provider-deployment", descriptor, Optional.of("acme"));

      Path socketPath = Files.createTempDirectory("gimle-deliver-config").resolve("worker.sock");
      try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        server.bind(UnixDomainSocketAddress.of(socketPath));
        try (SocketChannel workerRaw = SocketChannel.open(StandardProtocolFamily.UNIX)) {
          workerRaw.connect(UnixDomainSocketAddress.of(socketPath));
          try (SocketChannel agentRaw = server.accept()) {
            WorkerConnection agentSide = new WorkerConnection(agentRaw);
            SupervisedInstance instance = supervisedInstance(assigned, descriptor, agentSide);

            // Must return normally rather than let the ConnectException from the unreachable
            // Fafnir endpoint escape uncaught -- before the fix that IOException propagated out
            // of deliverConfig, through sendInstallStartSequence, leaving StartModule never sent
            // and the instance stuck at RESOLVED forever.
            AgentMain.deliverConfig(
                instance, agentSide, HttpClient.newHttpClient(), baseUrl, fafnirBaseUrl);
          }
        }
      } finally {
        Files.deleteIfExists(socketPath);
        Files.deleteIfExists(socketPath.getParent());
      }
    } finally {
      configServer.stop(0);
    }
  }

  @Test
  @Timeout(15)
  void deliver_config_degrades_gracefully_when_control_plane_config_is_unreachable()
      throws Exception {
    // Mirrors the Fafnir-unreachable test above, but isolates the sibling gap: a connection
    // failure hitting the control plane's own /config/{tenantId} (fetchConfigForTenant, also
    // declared throws IOException) used to escape deliverConfig's plain-config try block
    // uncaught, since it only caught InterruptedException/RuntimeException -- stranding the
    // instance exactly like the Fafnir case did, even with no Fafnir endpoint configured at all.
    URI baseUrl;
    try (ServerSocket unusedConfigPort = new ServerSocket(0)) {
      baseUrl = URI.create("http://127.0.0.1:" + unusedConfigPort.getLocalPort());
    }

    ModuleDescriptor descriptor = descriptor("provider", IsolationTier.TIER_2);
    AssignedInstance assigned =
        assignedInstance("provider-deployment", descriptor, Optional.of("acme"));

    Path socketPath = Files.createTempDirectory("gimle-deliver-config").resolve("worker.sock");
    try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socketPath));
      try (SocketChannel workerRaw = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        workerRaw.connect(UnixDomainSocketAddress.of(socketPath));
        try (SocketChannel agentRaw = server.accept()) {
          WorkerConnection agentSide = new WorkerConnection(agentRaw);
          SupervisedInstance instance = supervisedInstance(assigned, descriptor, agentSide);

          // Must return normally (no Fafnir endpoint configured, so nothing to fall through to)
          // rather than let the ConnectException from the unreachable control plane escape
          // uncaught -- before the fix that IOException propagated out of deliverConfig, through
          // sendInstallStartSequence, leaving StartModule never sent and the instance stuck at
          // RESOLVED forever.
          AgentMain.deliverConfig(instance, agentSide, HttpClient.newHttpClient(), baseUrl, null);
        }
      }
    } finally {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(socketPath.getParent());
    }
  }

  // ---- deliverConfig: secretMapRefs narrows secret delivery to just the named SecretMaps ----

  /**
   * The one test that proves the actual point of Phase 1's SecretMap work: a deployment declaring
   * {@code secretMapRefs} must receive only those SecretMaps' keys as {@link
   * ControlMessage.ConfigDelivered} messages, never the tenant's other secrets -- unlike today's
   * unscoped behavior (every secret, always), which is what an empty {@code secretMapRefs} still
   * gets. The fake Fafnir below serves both {@code /secrets/{tenantId}} (the flat, unscoped
   * listing) and {@code /secretmaps/{tenantId}?names=...} (the narrowed batch fetch); a hit counter
   * on the flat listing proves {@code deliverConfig} never even calls it once {@code secretMapRefs}
   * is non-empty, not just that its result happens to be filtered out afterward.
   */
  @Test
  @Timeout(15)
  void secret_map_refs_narrows_delivery_to_only_the_named_secretmaps_keys() throws Exception {
    java.util.concurrent.atomic.AtomicInteger flatSecretsListHits =
        new java.util.concurrent.atomic.AtomicInteger();

    HttpServer configServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    configServer.createContext("/config/acme", exchange -> respondJsonArray(exchange, "[]"));
    configServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    configServer.start();

    HttpServer fafnirServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fafnirServer.createContext(
        "/secrets/acme",
        exchange -> {
          flatSecretsListHits.incrementAndGet();
          respondJsonArray(exchange, "{\"secrets\": []}");
        });
    fafnirServer.createContext(
        "/secretmaps/acme",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          if (query == null || !query.contains("names=db-creds")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          String usernameBase64 =
              java.util.Base64.getEncoder()
                  .encodeToString("admin".getBytes(StandardCharsets.UTF_8));
          respondJsonArray(
              exchange,
              "{\"secretMaps\": {\"db-creds\": {\"data\": {\"username\": \""
                  + usernameBase64
                  + "\"}}}}");
        });
    fafnirServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    fafnirServer.start();

    try {
      URI baseUrl = URI.create("http://127.0.0.1:" + configServer.getAddress().getPort());
      URI fafnirBaseUrl = URI.create("http://127.0.0.1:" + fafnirServer.getAddress().getPort());

      ModuleDescriptor descriptor = descriptor("provider", IsolationTier.TIER_2);
      AssignedInstance assigned =
          new AssignedInstance(
              "provider-deployment",
              0,
              descriptor.id(),
              "/does/not/matter.jar",
              Optional.of("acme"),
              OptionalInt.empty(),
              Optional.empty(),
              List.of(),
              List.of("db-creds"));

      Path socketPath = Files.createTempDirectory("gimle-deliver-secretmap").resolve("worker.sock");
      try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        server.bind(UnixDomainSocketAddress.of(socketPath));
        try (SocketChannel workerRaw = SocketChannel.open(StandardProtocolFamily.UNIX)) {
          workerRaw.connect(UnixDomainSocketAddress.of(socketPath));
          try (SocketChannel agentRaw = server.accept()) {
            WorkerConnection agentSide = new WorkerConnection(agentRaw);
            SupervisedInstance instance = supervisedInstance(assigned, descriptor, agentSide);

            AgentMain.deliverConfig(
                instance, agentSide, HttpClient.newHttpClient(), baseUrl, fafnirBaseUrl);

            WorkerConnection workerSide = new WorkerConnection(workerRaw);
            ControlMessage.ConfigDelivered delivered =
                (ControlMessage.ConfigDelivered) workerSide.receive().orElseThrow();
            assertEquals("username", delivered.key());
            assertEquals("admin", delivered.value());
            assertTrue(delivered.wasEncrypted());

            // Nothing else was delivered -- the tenant's unrelated flat secrets never arrived.
            agentSide.send(new ControlMessage.ConfigDelivered("__sentinel__", "", false));
            ControlMessage next = workerSide.receive().orElseThrow();
            assertEquals("__sentinel__", ((ControlMessage.ConfigDelivered) next).key());

            assertEquals(
                0,
                flatSecretsListHits.get(),
                "deliverConfig must never fetch the unscoped flat secret list once secretMapRefs"
                    + " is declared");
          }
        }
      } finally {
        Files.deleteIfExists(socketPath);
        Files.deleteIfExists(socketPath.getParent());
      }
    } finally {
      configServer.stop(0);
      fafnirServer.stop(0);
    }
  }

  private static void respondJsonArray(com.sun.net.httpserver.HttpExchange exchange, String json)
      throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      in.readAllBytes();
    }
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
