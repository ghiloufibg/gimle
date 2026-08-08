package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.nio.file.Path;
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
}
