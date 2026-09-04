package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression: {@link AgentMain#prepareResourceLimit} must size a worker from the manifest's
 * resource *limit* rather than its request at Tier 2, and from the node's shared-worker budget at
 * Tier 1 where no single instance owns the heap; {@link AgentMain#buildWorkerCommand} must then
 * carry that size's {@code -Xmx} into the spawned worker's command line. Both are exercised
 * directly, not through the full {@code startInstance}/process-spawning path, which {@code
 * AgentWorkerIntegrationTest} and {@code ResourceLimitEnforcementTest} already cover with a
 * hand-built command that never goes through either of these call sites.
 */
class AgentMainTest {

  // ---- tick loop: a fatal Error halts the agent rather than only killing the main thread ----

  @Test
  void a_fatal_error_during_a_tick_halts_the_process_with_the_workers_own_oom_exit_code() {
    // Regression: the tick loop's own catch clause used to catch only RuntimeException/IOException,
    // so an uncaught OutOfMemoryError (the exact failure mode a burst of allocation while
    // reconciling a large set of pre-existing deployments against this agent's own fixed heap can
    // trigger) killed only the main thread -- every other non-daemon thread the agent started
    // (gossip, the admin API, the relays) kept the process alive as a zombie that never ticks or
    // heartbeats again. handleFatalTickError is what the tick loop's catch (Error e) now calls;
    // exercised directly here (with a recording stub instead of Runtime.getRuntime()::halt) so the
    // test process itself is not terminated.
    List<Integer> exitCodes = new ArrayList<>();
    AgentMain.handleFatalTickError(new OutOfMemoryError("simulated"), exitCodes::add);

    assertEquals(List.of(WorkerProcessSupervisor.OOM_EXIT_CODE), exitCodes);
  }

  @Test
  void a_fatal_error_on_any_other_agent_thread_also_halts_the_process()
      throws InterruptedException {
    // The tick loop's own catch (Error e) above only ever covers that one thread. This agent
    // starts several others (gossip, the admin API, the config/network-policy relays, Bifrost)
    // with no explicit guard of their own -- main installs defaultUncaughtExceptionHandler as the
    // JVM's own Thread.setDefaultUncaughtExceptionHandler precisely so an OutOfMemoryError on any
    // of *those* threads halts the process the same way, rather than silently killing just that
    // one thread and leaving the agent a zombie. Driven against a real throwaway thread (not
    // called directly) so this actually exercises Thread.UncaughtExceptionHandler dispatch, not
    // just the handler's own body.
    List<Integer> exitCodes = new ArrayList<>();
    Thread.UncaughtExceptionHandler handler =
        AgentMain.defaultUncaughtExceptionHandler(exitCodes::add);
    Thread thread =
        new Thread(
            () -> {
              throw new OutOfMemoryError("simulated, on a background thread");
            },
            "some-background-thread");
    thread.setUncaughtExceptionHandler(handler);
    thread.start();
    thread.join();

    assertEquals(List.of(WorkerProcessSupervisor.OOM_EXIT_CODE), exitCodes);
  }

  @Test
  void an_ordinary_runtime_exception_on_another_thread_does_not_halt_the_process()
      throws InterruptedException {
    // An uncaught Error is treated as fatal to the whole process; an ordinary RuntimeException is
    // not -- that thread dying alone (the JVM's own default uncaught-exception behavior, just
    // logged through this agent's own logger instead) is the existing, unremarkable outcome for a
    // real bug in background work, not a reason to halt every other thread's work along with it.
    List<Integer> exitCodes = new ArrayList<>();
    Thread.UncaughtExceptionHandler handler =
        AgentMain.defaultUncaughtExceptionHandler(exitCodes::add);
    Thread thread =
        new Thread(
            () -> {
              throw new IllegalStateException("simulated, ordinary bug");
            },
            "some-other-background-thread");
    thread.setUncaughtExceptionHandler(handler);
    thread.start();
    thread.join();

    assertEquals(List.of(), exitCodes);
  }

  private static final ResourceSpec REQUEST = new ResourceSpec("16Mi", "500m");
  private static final ResourceSpec LIMIT = new ResourceSpec("64Mi", "2000m");

  /**
   * The shared-worker budget an agent runs with when nothing overrides it -- read from the defaults
   * rather than hand-written here, so a change to those defaults moves these tests with it instead
   * of leaving them asserting against a number the agent no longer uses.
   */
  private static final Tier1WorkerBudget DEFAULT_TIER1_BUDGET =
      Tier1WorkerBudget.parse(null, null, null);

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
        Optional.empty(),
        Optional.empty());
  }

  private static List<String> buildDefaultWorkerCommand(
      PortableJvmFlagsResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      AssignedInstance assigned) {
    return buildDefaultWorkerCommand(resourceLimiter, handle, assigned, "node-1");
  }

  private static ModuleDescriptor descriptorWithDistinctRequestAndLimit() {
    return descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_1);
  }

  private static ModuleDescriptor descriptorWithDistinctRequestAndLimit(IsolationTier tier) {
    return new ModuleDescriptor(
        "hello-module",
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        tier,
        REQUEST,
        LIMIT,
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  @Test
  void a_tier2_worker_is_sized_by_the_descriptors_limit_not_its_request() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_2);
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();

    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);

    assertEquals(LIMIT.memoryBytes(), handle.limit().memoryBytes());
    assertEquals(LIMIT.cpuMillicores(), handle.limit().cpuMillicores());
  }

  @Test
  void a_tier1_worker_is_sized_by_the_shared_budget_not_by_whichever_instance_spawned_it() {
    // The heap of a shared worker is not any one instance's to set: several instances run behind
    // it, and sizing it from the first arrival is what made every later arrival's declared limit
    // both unenforced and unpredictable.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_1);
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();

    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);

    assertEquals(DEFAULT_TIER1_BUDGET.heapBytes(), handle.limit().memoryBytes());
    assertEquals(DEFAULT_TIER1_BUDGET.cpuMillicores(), handle.limit().cpuMillicores());
  }

  @Test
  void the_spawned_command_carries_the_manifests_limit_not_its_request() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_2);
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
  void the_spawned_command_forwards_the_fabric_max_connections_flag() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    String property = "gimle.fabric.maxConnections";
    String previous = System.getProperty(property);
    try {
      // Absent -> forwarded as "512", not simply omitted -- every worker gets an explicit value
      // rather than silently inheriting whatever FabricServer's own default happens to be.
      System.clearProperty(property);
      List<String> withoutProperty = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);
      assertTrue(
          withoutProperty.contains("-Dgimle.fabric.maxConnections=512"),
          "expected the flag forwarded as 512 by default; command=" + withoutProperty);

      System.setProperty(property, "128");
      List<String> withProperty = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);
      assertTrue(
          withProperty.contains("-Dgimle.fabric.maxConnections=128"),
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
  void the_spawned_command_omits_tls_flags_in_plaintext_mode() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    String property = "gimle.transport.protocol";
    String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      List<String> command = buildDefaultWorkerCommand(resourceLimiter, handle, assigned);
      assertTrue(
          command.stream().noneMatch(flag -> flag.startsWith("-Dgimle.transport.protocol")),
          "expected no transport.protocol flag in plaintext mode; command=" + command);
      assertTrue(
          command.stream().noneMatch(flag -> flag.startsWith("-Dgimle.tls.")),
          "expected no TLS flags in plaintext mode; command=" + command);
    } finally {
      restoreProperty(property, previous);
    }
  }

  /**
   * The worker presents its own certificate, never this agent's node certificate: the cert/key
   * paths in the spawned command are the per-worker material {@code WorkerCertificates} issued, and
   * only the cluster CA file is the agent's own. A node certificate handed down to every worker
   * would let hosted-module code act as the node, and would give a receiving fabric listener no
   * tenant to read off the connection.
   */
  @Test
  void the_spawned_command_forwards_the_workers_own_certificate_and_the_shared_ca_under_tls(
      @TempDir Path tempDir) throws IOException {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    // TlsSettings only checks Files.isRegularFile, never parses PEM content, so empty files are
    // enough here -- no need to mint real certificate material from gimle-pki for this test.
    Path nodeCertFile = Files.createFile(tempDir.resolve("node.crt"));
    Path nodeKeyFile = Files.createFile(tempDir.resolve("node.key"));
    Path caFile = Files.createFile(tempDir.resolve("ca.crt"));
    WorkerCertificates.Material workerMaterial =
        new WorkerCertificates.Material(
            tempDir.resolve("workers/hello-deployment#0/worker.crt"),
            tempDir.resolve("workers/hello-deployment#0/worker.key"));
    String previousProtocol = System.getProperty("gimle.transport.protocol");
    String previousCert = System.getProperty("gimle.tls.certFile");
    String previousKey = System.getProperty("gimle.tls.keyFile");
    String previousCa = System.getProperty("gimle.tls.caFile");
    try {
      System.setProperty("gimle.transport.protocol", "tls");
      System.setProperty("gimle.tls.certFile", nodeCertFile.toString());
      System.setProperty("gimle.tls.keyFile", nodeKeyFile.toString());
      System.setProperty("gimle.tls.caFile", caFile.toString());

      List<String> command =
          AgentMain.buildWorkerCommand(
              "java",
              List.of("-cp", "worker.jar", "com.gimle.worker.WorkerMain"),
              resourceLimiter,
              handle,
              Path.of("gimle-logs", "workers", "hello-deployment#0"),
              "node-1",
              assigned,
              Optional.empty(),
              Optional.of(workerMaterial));

      assertTrue(
          command.contains("-Dgimle.transport.protocol=tls"),
          "expected the transport protocol forwarded verbatim; command=" + command);
      assertTrue(
          command.contains("-Dgimle.tls.certFile=" + workerMaterial.certFile()),
          "expected the worker's own cert file path; command=" + command);
      assertTrue(
          command.contains("-Dgimle.tls.keyFile=" + workerMaterial.keyFile()),
          "expected the worker's own key file path; command=" + command);
      assertTrue(
          command.contains("-Dgimle.tls.caFile=" + caFile),
          "expected the shared CA file path forwarded verbatim; command=" + command);
      assertTrue(
          command.stream().noneMatch(flag -> flag.endsWith("=" + nodeCertFile)),
          "the agent's own node certificate must never reach a worker; command=" + command);
      assertTrue(
          command.stream().noneMatch(flag -> flag.endsWith("=" + nodeKeyFile)),
          "the agent's own node key must never reach a worker; command=" + command);
    } finally {
      restoreProperty("gimle.transport.protocol", previousProtocol);
      restoreProperty("gimle.tls.certFile", previousCert);
      restoreProperty("gimle.tls.keyFile", previousKey);
      restoreProperty("gimle.tls.caFile", previousCa);
    }
  }

  /**
   * TLS material is per worker, so it can never sit in the flags every worker shares -- those feed
   * the Sleipnir AOT cache key, which must not change from one instance to the next.
   */
  @Test
  void stable_worker_flags_carry_no_tls_material_even_under_tls(@TempDir Path tempDir)
      throws IOException {
    Path certFile = Files.createFile(tempDir.resolve("node.crt"));
    Path keyFile = Files.createFile(tempDir.resolve("node.key"));
    Path caFile = Files.createFile(tempDir.resolve("ca.crt"));
    String previousProtocol = System.getProperty("gimle.transport.protocol");
    String previousCert = System.getProperty("gimle.tls.certFile");
    String previousKey = System.getProperty("gimle.tls.keyFile");
    String previousCa = System.getProperty("gimle.tls.caFile");
    try {
      System.setProperty("gimle.transport.protocol", "tls");
      System.setProperty("gimle.tls.certFile", certFile.toString());
      System.setProperty("gimle.tls.keyFile", keyFile.toString());
      System.setProperty("gimle.tls.caFile", caFile.toString());

      List<String> flags = AgentMain.stableWorkerFlags();

      assertTrue(
          flags.stream().noneMatch(flag -> flag.startsWith("-Dgimle.tls.")),
          "expected no TLS material in the stable flags; flags=" + flags);
      assertTrue(
          flags.stream().noneMatch(flag -> flag.startsWith("-Dgimle.transport.protocol")),
          "expected no transport flag in the stable flags; flags=" + flags);
    } finally {
      restoreProperty("gimle.transport.protocol", previousProtocol);
      restoreProperty("gimle.tls.certFile", previousCert);
      restoreProperty("gimle.tls.keyFile", previousKey);
      restoreProperty("gimle.tls.caFile", previousCa);
    }
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  @Test
  void the_spawned_command_always_suppresses_the_startup_banner() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
        AgentMain.prepareResourceLimit(
            resourceLimiter, "hello-deployment#0", descriptor, DEFAULT_TIER1_BUDGET);
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
            Optional.of(cachePath),
            Optional.empty());

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
  void observation_json_falls_back_to_the_declared_limit_with_no_real_worker_behind_it() {
    // Without these, a reader has usage numbers and no ceiling to read them against -- 142Mi tells
    // an operator nothing about whether the instance is comfortable or about to die. This
    // particular instance was never actually spawned (workerLimit null, as only a unit test does),
    // so the descriptor's own declared limit is the only ceiling there is to report.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals("TIER_1", observation.get("isolationTier"));
    // The limit, never the request: the request is a scheduling input, the limit is the ceiling
    // the instance actually runs under.
    assertEquals(Map.of("memory", "64Mi", "cpu", "2000m"), observation.get("resourceLimit"));
  }

  @Test
  void observation_json_reports_the_real_shared_worker_ceiling_for_a_tier1_instance() {
    // Regression test for the bug where a TIER_1 instance's reported resourceLimit was its own
    // declared 64Mi/2000m manifest limit -- a number with no relationship to the JVM it actually
    // runs inside, which is a shared worker sized by Tier1WorkerBudget, not by this instance's own
    // manifest. workerLimit (populated from the real spawn) must win over the descriptor here.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_1);
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    ResourceSpec sharedWorkerCeiling = new ResourceSpec("1Gi", "2000m");
    SupervisedInstance instance =
        new SupervisedInstance(assigned, null, null, descriptor, "worker-key", sharedWorkerCeiling);
    instance.lifecycleState = "ACTIVE";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals("TIER_1", observation.get("isolationTier"));
    assertEquals(Map.of("memory", "1Gi", "cpu", "2000m"), observation.get("resourceLimit"));
  }

  @Test
  void observation_json_reports_the_modules_own_declared_limit_for_a_tier2_instance() {
    // No regression: at TIER_2 the instance owns its worker outright, so workerLimit (the real
    // spawn size) and the descriptor's own declared limit are the same ResourceSpec -- reporting
    // workerLimit here must still read as "the module's own declared limit", unchanged from before.
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit(IsolationTier.TIER_2);
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance =
        new SupervisedInstance(
            assigned, null, null, descriptor, "worker-key", descriptor.resourceLimit());
    instance.lifecycleState = "ACTIVE";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals("TIER_2", observation.get("isolationTier"));
    assertEquals(Map.of("memory", "64Mi", "cpu", "2000m"), observation.get("resourceLimit"));
  }

  @Test
  void observation_json_omits_the_tier_and_limit_when_no_descriptor_is_held() {
    AssignedInstance assigned =
        new AssignedInstance(
            "orders-service",
            0,
            new ModuleId("com.acme.orders", Version.parse("1.0.0")),
            "/does/not/matter.jar",
            Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, null);
    instance.lifecycleState = "ACTIVE";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertFalse(observation.containsKey("isolationTier"));
    assertFalse(observation.containsKey("resourceLimit"));
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
    // ServiceEndpointResolver resolve a live endpoint for an ordinary module deployment, not
    // just a Vessel workload.
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
  void observation_json_omits_worker_id_until_the_workers_hello_arrives() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "web-ui", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "STARTING";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertFalse(observation.containsKey("workerId"));
  }

  @Test
  void observation_json_reports_the_workers_self_reported_id_once_its_hello_arrives() {
    ModuleDescriptor descriptor = descriptorWithDistinctRequestAndLimit();
    AssignedInstance assigned =
        new AssignedInstance(
            "web-ui", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.lifecycleState = "ACTIVE";
    instance.fabricWorkerId = "worker-4821";

    Map<String, Object> observation = AgentMain.observationJson(instance);

    assertEquals("worker-4821", observation.get("workerId"));
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
        Map.of());
  }

  private static ModuleDescriptor descriptorWithLimit(String name, String memory, String cpu) {
    return new ModuleDescriptor(
        name,
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        new ResourceSpec("4Mi", "100m"),
        new ResourceSpec(memory, cpu),
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  private static AssignedInstance assignedInstance(
      String deploymentName, ModuleDescriptor descriptor, Optional<String> tenantId) {
    return new AssignedInstance(
        deploymentName, 0, descriptor.id(), "/does/not/matter.jar", tenantId);
  }

  /**
   * A real (but never connected) {@link SocketChannel}-backed {@link WorkerConnection} -- only its
   * object identity matters here (a null vs. non-null connection is what {@code
   * installIntoExistingWorker}'s own connect/join split cares about), not its ability to actually
   * send/receive.
   */
  private static WorkerConnection fakeConnection() {
    try {
      return new WorkerConnection(SocketChannel.open());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Stands in for the *owning* instance of a worker -- carries {@code key} as its own {@code
   * workerKey}, exactly the invariant {@code AgentMain#startInstance} establishes for a real spawn
   * ("its own key for an instance that got a freshly spawned worker" -- see {@link
   * SupervisedInstance#workerKey}'s own javadoc). {@code connection} may be {@code null} to
   * simulate a worker whose spawn hasn't finished connecting yet -- {@code findReusableTier1Worker}
   * must still be able to group and admit against such an owner, since that is exactly the state
   * every instance in a same-tick batch is in.
   */
  private static SupervisedInstance ownerInstance(
      String key,
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      WorkerConnection connection) {
    return ownerInstance(key, assigned, descriptor, connection, null);
  }

  private static SupervisedInstance ownerInstance(
      String key,
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      WorkerConnection connection,
      ResourceSpec workerLimit) {
    SupervisedInstance instance =
        new SupervisedInstance(assigned, null, null, descriptor, key, workerLimit);
    instance.connection = connection;
    return instance;
  }

  /**
   * Stands in for an instance already packed onto {@code ownerKey}'s worker -- carries {@code
   * ownerKey}, not its own map key, as {@code workerKey}, exactly what {@code
   * AgentMain#installIntoExistingWorker} does for a real packed instance.
   */
  private static SupervisedInstance packedInstance(
      String ownerKey,
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      WorkerConnection connection) {
    SupervisedInstance instance =
        new SupervisedInstance(assigned, null, null, descriptor, ownerKey, null);
    instance.connection = connection;
    return instance;
  }

  /**
   * A bare {@link SupervisedInstance} with no worker behind it at all -- {@code workerKey} null.
   */
  private static SupervisedInstance supervisedInstance(
      AssignedInstance assigned, ModuleDescriptor descriptor, WorkerConnection connection) {
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.connection = connection;
    return instance;
  }

  /** The same, for a worker whose real spawn-time sizing the budget check has to read back. */
  private static SupervisedInstance supervisedInstance(
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      WorkerConnection connection,
      ResourceSpec workerLimit) {
    SupervisedInstance instance =
        new SupervisedInstance(assigned, null, null, descriptor, "worker", workerLimit);
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
        ownerInstance(
            "provider-deployment#0", existingAssigned, existingDescriptor, sharedConnection);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("provider-deployment#0", existing);

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.of("acme"));

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            newAssigned,
            newDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

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
        ownerInstance(
            "provider-deployment#0", existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor tier2Descriptor = descriptor("dedicated", IsolationTier.TIER_2);
    AssignedInstance tier2Assigned =
        assignedInstance("dedicated-deployment", tier2Descriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            tier2Assigned,
            tier2Descriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

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
        ownerInstance(
            "provider-deployment#0", existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.of("other-tenant"));

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            newAssigned,
            newDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

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
        ownerInstance(
            "provider-deployment#0", existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            newAssigned,
            newDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

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
        "greeter-deployment#0",
        ownerInstance("greeter-deployment#0", replicaZero, descriptor, sharedConnection));

    AssignedInstance replicaOne =
        new AssignedInstance(
            "greeter-deployment", 1, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            replicaOne,
            descriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

    assertFalse(reusable.isPresent());
  }

  @Test
  void a_worker_at_the_density_cap_is_not_reused() {
    WorkerConnection sharedConnection = fakeConnection();
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    String ownerKey = "occupant-0-deployment#0";
    // Fill the shared worker up to the default density cap with that many distinct modules.
    for (int i = 0; i < AgentMain.DEFAULT_MAX_TIER1_DENSITY; i++) {
      ModuleDescriptor occupantDescriptor = descriptor("occupant-" + i, IsolationTier.TIER_1);
      AssignedInstance occupantAssigned =
          assignedInstance("occupant-" + i + "-deployment", occupantDescriptor, Optional.empty());
      String key = "occupant-" + i + "-deployment#0";
      supervised.put(
          key,
          i == 0
              ? ownerInstance(ownerKey, occupantAssigned, occupantDescriptor, sharedConnection)
              : packedInstance(ownerKey, occupantAssigned, occupantDescriptor, sharedConnection));
    }

    ModuleDescriptor newDescriptor = descriptor("one-too-many", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("one-too-many-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            newAssigned,
            newDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

    assertFalse(reusable.isPresent());
  }

  @Test
  void a_worker_with_no_established_connection_yet_is_still_reused() {
    // The fix for the real-world failure this method exists to prevent: a batch of brand-new
    // Tier-1 instances arriving in the very same reconcile tick all spawn or join a worker before
    // any of them has actually finished connecting -- connection is still null here, the same as
    // it would be moments after AgentMain#startInstance returns. Refusing to pack onto a
    // not-yet-connected worker (the previous behavior this test used to assert) meant every
    // instance in such a batch always spawned its own worker, since none of them could ever see a
    // same-tick sibling as "already connected" -- "one worker per instance, zero sharing"
    // regardless of the configured density cap. See also
    // twelve_tier1_instances_arriving_in_one_batch_share_fewer_than_twelve_workers below.
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0",
        ownerInstance("provider-deployment#0", existingAssigned, existingDescriptor, null));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    Optional<SupervisedInstance> reusable =
        AgentMain.findReusableTier1Worker(
            newAssigned,
            newDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            DEFAULT_TIER1_BUDGET);

    assertTrue(reusable.isPresent());
  }

  @Test
  void an_owner_that_has_since_been_removed_leaves_nothing_to_reuse() {
    // A packed instance can outlive the map entry its own workerKey points at (the owning
    // instance renamed or torn down while it lives on) -- findReusableTier1Worker must not treat
    // a worker with no resolvable owner as reusable, since there is no connection or workerLimit
    // left to read for it.
    ModuleDescriptor residentDescriptor = descriptor("resident", IsolationTier.TIER_1);
    AssignedInstance residentAssigned =
        assignedInstance("resident-deployment", residentDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "resident-deployment#0",
        packedInstance("owner-gone#0", residentAssigned, residentDescriptor, fakeConnection()));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    assertFalse(
        AgentMain.findReusableTier1Worker(
                newAssigned,
                newDescriptor,
                supervised,
                AgentMain.DEFAULT_MAX_TIER1_DENSITY,
                DEFAULT_TIER1_BUDGET)
            .isPresent());
  }

  // ---- Tier 1 density: the summed-limit budget ----

  @Test
  void a_worker_is_not_reused_once_the_residents_declared_limits_fill_its_heap() {
    // The density cap counts instances; this weighs them. A worker under its instance count but
    // already committed to its whole heap must not take another claim -- admitting it would mean
    // every co-tenant simultaneously reaching the bound its own manifest promises is the case that
    // OOMs the JVM they share.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("256Mi", "4000m", "32Mi");
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor residentDescriptor = descriptorWithLimit("resident", "200Mi", "500m");
    AssignedInstance residentAssigned =
        assignedInstance("resident-deployment", residentDescriptor, Optional.of("acme"));
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "resident-deployment#0",
        ownerInstance(
            "resident-deployment#0",
            residentAssigned,
            residentDescriptor,
            sharedConnection,
            budget.sizeFor(residentDescriptor)));

    ModuleDescriptor candidateDescriptor = descriptorWithLimit("candidate", "64Mi", "500m");
    AssignedInstance candidateAssigned =
        assignedInstance("candidate-deployment", candidateDescriptor, Optional.of("acme"));

    // 200Mi + 64Mi = 264Mi against a 256Mi worker with 32Mi held back for the JVM itself.
    assertFalse(
        AgentMain.findReusableTier1Worker(
                candidateAssigned,
                candidateDescriptor,
                supervised,
                AgentMain.DEFAULT_MAX_TIER1_DENSITY,
                budget)
            .isPresent());
  }

  @Test
  void a_worker_is_reused_while_the_declared_limits_still_fit_inside_its_heap() {
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("256Mi", "4000m", "32Mi");
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor residentDescriptor = descriptorWithLimit("resident", "128Mi", "500m");
    AssignedInstance residentAssigned =
        assignedInstance("resident-deployment", residentDescriptor, Optional.of("acme"));
    SupervisedInstance resident =
        ownerInstance(
            "resident-deployment#0",
            residentAssigned,
            residentDescriptor,
            sharedConnection,
            budget.sizeFor(residentDescriptor));
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("resident-deployment#0", resident);

    ModuleDescriptor candidateDescriptor = descriptorWithLimit("candidate", "64Mi", "500m");
    AssignedInstance candidateAssigned =
        assignedInstance("candidate-deployment", candidateDescriptor, Optional.of("acme"));

    // 128Mi + 64Mi = 192Mi, inside the 224Mi left once the overhead reserve is held back.
    assertEquals(
        Optional.of(resident),
        AgentMain.findReusableTier1Worker(
            candidateAssigned,
            candidateDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            budget));
  }

  @Test
  void a_module_larger_than_the_whole_budget_gets_a_worker_to_itself() {
    // The inverse of the arbitrary-sizing bug: a module declaring more heap than a shared worker
    // holds must not be strangled by that budget, so it gets a worker sized to its own manifest --
    // and that worker is then, correctly, full, since the one instance on it has already claimed
    // every byte it was given.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("128Mi", "4000m", "32Mi");
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor residentDescriptor = descriptorWithLimit("resident", "512Mi", "500m");
    AssignedInstance residentAssigned =
        assignedInstance("resident-deployment", residentDescriptor, Optional.of("acme"));
    ResourceSpec workerSize = budget.sizeFor(residentDescriptor);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "resident-deployment#0",
        supervisedInstance(residentAssigned, residentDescriptor, sharedConnection, workerSize));

    assertEquals(new ResourceSpec("544Mi", "4000m").memoryBytes(), workerSize.memoryBytes());

    ModuleDescriptor candidateDescriptor = descriptorWithLimit("candidate", "8Mi", "500m");
    AssignedInstance candidateAssigned =
        assignedInstance("candidate-deployment", candidateDescriptor, Optional.of("acme"));

    assertFalse(
        AgentMain.findReusableTier1Worker(
                candidateAssigned,
                candidateDescriptor,
                supervised,
                AgentMain.DEFAULT_MAX_TIER1_DENSITY,
                budget)
            .isPresent());
  }

  @Test
  void a_worker_is_not_offered_for_reuse_once_the_instance_that_owns_it_is_gone() {
    // findReusableTier1Worker groups by workerKey and then looks up that key's own entry in
    // `supervised` to find the owner -- the one instance the density javadoc names as the source of
    // truth for the worker's real spawn-time size. A packed sibling surviving its owner's teardown
    // does not get promoted to stand in for it: the group is treated as "nothing to reuse" rather
    // than reasoning about a worker with no owner left to ask, even though the sibling itself is
    // still running fine on it. This is a real, accepted trade-off, not an oversight -- see
    // findReusableTier1Worker's own javadoc for why.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("128Mi", "4000m", "32Mi");
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor spawnerDescriptor = descriptorWithLimit("spawner", "512Mi", "500m");
    ResourceLimitHandle spawned =
        AgentMain.prepareResourceLimit(
            new PortableJvmFlagsResourceLimiter(),
            "spawner-deployment#0",
            spawnerDescriptor,
            budget);
    ModuleDescriptor survivorDescriptor = descriptorWithLimit("survivor", "8Mi", "500m");
    AssignedInstance survivorAssigned =
        assignedInstance("survivor-deployment", survivorDescriptor, Optional.of("acme"));
    // workerKey ("worker", from the 4-arg helper) never has its own entry in `supervised` --
    // standing in for the spawner having since been torn down while this packed sibling survives.
    SupervisedInstance survivor =
        supervisedInstance(survivorAssigned, survivorDescriptor, sharedConnection, spawned.limit());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("survivor-deployment#0", survivor);

    ModuleDescriptor candidateDescriptor = descriptorWithLimit("candidate", "256Mi", "500m");
    AssignedInstance candidateAssigned =
        assignedInstance("candidate-deployment", candidateDescriptor, Optional.of("acme"));

    assertEquals(
        Optional.empty(),
        AgentMain.findReusableTier1Worker(
            candidateAssigned,
            candidateDescriptor,
            supervised,
            AgentMain.DEFAULT_MAX_TIER1_DENSITY,
            budget));
  }

  // ---- Tier 1 density: the gimle.agent.maxTier1Density knob ----

  @Test
  void an_unset_density_property_keeps_the_documented_default() {
    assertEquals(AgentMain.DEFAULT_MAX_TIER1_DENSITY, AgentMain.parseMaxTier1Density(null));
    assertEquals(AgentMain.DEFAULT_MAX_TIER1_DENSITY, AgentMain.parseMaxTier1Density("  "));
  }

  @Test
  void a_configured_density_is_honoured_verbatim() {
    assertEquals(12, AgentMain.parseMaxTier1Density("12"));
    assertEquals(1, AgentMain.parseMaxTier1Density(" 1 "));
  }

  @Test
  void a_zero_or_negative_density_is_rejected_at_startup() {
    // A silently-ignored setting is worse than a startup failure: the operator meant to change
    // the packing behavior and would have no way to tell that nothing happened.
    assertThrows(IllegalArgumentException.class, () -> AgentMain.parseMaxTier1Density("0"));
    assertThrows(IllegalArgumentException.class, () -> AgentMain.parseMaxTier1Density("-3"));
  }

  @Test
  void a_non_numeric_density_is_rejected_at_startup() {
    assertThrows(IllegalArgumentException.class, () -> AgentMain.parseMaxTier1Density("dense"));
  }

  @Test
  void a_density_of_one_disables_packing_entirely() {
    WorkerConnection sharedConnection = fakeConnection();
    ModuleDescriptor existingDescriptor = descriptor("provider", IsolationTier.TIER_1);
    AssignedInstance existingAssigned =
        assignedInstance("provider-deployment", existingDescriptor, Optional.empty());
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(
        "provider-deployment#0",
        ownerInstance(
            "provider-deployment#0", existingAssigned, existingDescriptor, sharedConnection));

    ModuleDescriptor newDescriptor = descriptor("consumer", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("consumer-deployment", newDescriptor, Optional.empty());

    assertFalse(
        AgentMain.findReusableTier1Worker(
                newAssigned, newDescriptor, supervised, 1, DEFAULT_TIER1_BUDGET)
            .isPresent());
  }

  @Test
  void a_raised_density_packs_past_what_the_default_would_have_allowed() {
    WorkerConnection sharedConnection = fakeConnection();
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    String ownerKey = "occupant-0-deployment#0";
    for (int i = 0; i < AgentMain.DEFAULT_MAX_TIER1_DENSITY; i++) {
      ModuleDescriptor occupantDescriptor = descriptor("occupant-" + i, IsolationTier.TIER_1);
      AssignedInstance occupantAssigned =
          assignedInstance("occupant-" + i + "-deployment", occupantDescriptor, Optional.empty());
      String key = "occupant-" + i + "-deployment#0";
      supervised.put(
          key,
          i == 0
              ? ownerInstance(ownerKey, occupantAssigned, occupantDescriptor, sharedConnection)
              : packedInstance(ownerKey, occupantAssigned, occupantDescriptor, sharedConnection));
    }

    ModuleDescriptor newDescriptor = descriptor("one-more", IsolationTier.TIER_1);
    AssignedInstance newAssigned =
        assignedInstance("one-more-deployment", newDescriptor, Optional.empty());

    assertTrue(
        AgentMain.findReusableTier1Worker(
                newAssigned,
                newDescriptor,
                supervised,
                AgentMain.DEFAULT_MAX_TIER1_DENSITY + 1,
                DEFAULT_TIER1_BUDGET)
            .isPresent());
  }

  // ---- Tier 1 density: a batch of new instances in one reconcile tick still packs ----

  @Test
  void twelve_tier1_instances_arriving_in_one_batch_share_fewer_than_twelve_workers() {
    // Reproduces the real-world failure directly: fetchAssignments returning many brand-new
    // Tier-1 instances at once (a scale-up, or a first reconcile after this agent restarts and
    // finds a pile of pre-existing assignments) used to spawn one worker per instance regardless
    // of the density cap, because none of a same-tick batch's connections are established yet.
    // Simulates that same-tick condition directly: every new instance below is placed with no
    // connection at all, exactly as AgentMain#startInstance leaves one immediately after
    // spawning, well before its driveInstanceUp thread's accept() could possibly have returned.
    int density = 4;
    int instanceCount = 12;
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    for (int i = 0; i < instanceCount; i++) {
      ModuleDescriptor instanceDescriptor = descriptor("small-" + i, IsolationTier.TIER_1);
      AssignedInstance assigned =
          assignedInstance("small-" + i + "-deployment", instanceDescriptor, Optional.empty());
      String key = "small-" + i + "-deployment#0";
      Optional<SupervisedInstance> reusable =
          AgentMain.findReusableTier1Worker(
              assigned, instanceDescriptor, supervised, density, DEFAULT_TIER1_BUDGET);
      SupervisedInstance instance =
          reusable.isPresent()
              ? packedInstance(reusable.get().workerKey, assigned, instanceDescriptor, null)
              : ownerInstance(key, assigned, instanceDescriptor, null);
      supervised.put(key, instance);
    }

    Set<String> workersUsed =
        supervised.values().stream()
            .map(instance -> instance.workerKey)
            .collect(Collectors.toSet());

    assertEquals(3, workersUsed.size());
    assertTrue(workersUsed.size() < instanceCount);
  }

  @Test
  void an_oversized_tier1_module_still_gets_its_own_dedicated_worker_sized_at_limit_plus_reserve() {
    // The companion case: however many small instances pack together, one that declares more
    // memory than a whole shared worker holds must never be folded in -- it gets a worker sized
    // exactly to its own limit plus the budget's overhead reserve (Tier1WorkerBudget#sizeFor), and
    // that worker is then correctly full, with nothing else able to join it.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("256Mi", "4000m", "32Mi");
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    String smallOwnerKey = "small-0-deployment#0";
    for (int i = 0; i < 3; i++) {
      ModuleDescriptor smallDescriptor = descriptorWithLimit("small-" + i, "8Mi", "100m");
      AssignedInstance smallAssigned =
          assignedInstance("small-" + i + "-deployment", smallDescriptor, Optional.empty());
      String key = "small-" + i + "-deployment#0";
      supervised.put(
          key,
          i == 0
              ? ownerInstance(
                  smallOwnerKey,
                  smallAssigned,
                  smallDescriptor,
                  null,
                  budget.sizeFor(smallDescriptor))
              : packedInstance(smallOwnerKey, smallAssigned, smallDescriptor, null));
    }

    ModuleDescriptor bigDescriptor = descriptorWithLimit("big", "300Mi", "500m");
    AssignedInstance bigAssigned =
        assignedInstance("big-deployment", bigDescriptor, Optional.empty());

    // 300Mi does not fit alongside the small instances (nor would it fit on its own, against a
    // 256Mi budget with 32Mi reserved) -- it must never be offered the small instances' worker.
    assertFalse(
        AgentMain.findReusableTier1Worker(bigAssigned, bigDescriptor, supervised, 10, budget)
            .isPresent());

    // And the size it is actually spawned at is its own declared limit plus the reserve, not the
    // node's nominal shared-worker size.
    PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ResourceLimitHandle handle =
        AgentMain.prepareResourceLimit(resourceLimiter, "big-deployment#0", bigDescriptor, budget);
    assertEquals(new ResourceSpec("332Mi", "500m").memoryBytes(), handle.limit().memoryBytes());
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
            Map.of());
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

  // ---- requiresVesselReplacement: a Vessel's runtime config lives directly in its own manifest
  // {@code vessel:} block (env/args/jvmFlags/files/probes/resources), not in a gimle-module.yaml
  // read off artifactPath the way a hosted module's does -- so this comparison must catch a
  // vessel() change on top of moduleId/artifactPath, or an edited env var/probe/mount is silently
  // never applied to the already-running process. ----

  private static VesselSpec vesselSpec(String greetingEnvValue) {
    return new VesselSpec(
        List.of(),
        List.of(),
        Map.of("GREETING", new VesselEnvValue.Literal(greetingEnvValue)),
        List.of(),
        VesselProbes.NONE,
        REQUEST,
        LIMIT);
  }

  private static SupervisedVessel supervisedVessel(AssignedInstance assigned) {
    return new SupervisedVessel(
        assigned, assigned.vessel().orElseThrow(), null, null, Map.of(), List.of(), Instant.now());
  }

  @Test
  void a_vessel_env_var_change_at_the_same_key_requires_replacement() {
    ModuleDescriptor descriptor = descriptor("gateway-vessel", IsolationTier.TIER_2);
    AssignedInstance original =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));
    SupervisedVessel existing = supervisedVessel(original);

    // Same moduleId/artifactPath -- only the vessel's own env value changed, exactly what an
    // operator editing the manifest's vessel: block looks like on the wire.
    AssignedInstance edited =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("goodbye")));

    assertTrue(AgentMain.requiresVesselReplacement(edited, existing));
  }

  @Test
  void a_vessel_probe_change_at_the_same_key_requires_replacement() {
    ModuleDescriptor descriptor = descriptor("gateway-vessel", IsolationTier.TIER_2);
    // An http probe rung requires at least one {port: ...} env entry to dial -- add one here so
    // withLivenessProbe below is itself a valid VesselSpec.
    VesselSpec withoutProbes =
        new VesselSpec(
            List.of(),
            List.of(),
            Map.of(
                "GREETING", new VesselEnvValue.Literal("hello"),
                "PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty())),
            List.of(),
            VesselProbes.NONE,
            REQUEST,
            LIMIT);
    AssignedInstance original =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(withoutProbes));
    SupervisedVessel existing = supervisedVessel(original);

    VesselSpec withLivenessProbe =
        new VesselSpec(
            withoutProbes.args(),
            withoutProbes.jvmFlags(),
            withoutProbes.env(),
            withoutProbes.files(),
            new VesselProbes(Optional.of(new VesselProbeSpec.Http("/health")), Optional.empty()),
            withoutProbes.resourceRequest(),
            withoutProbes.resourceLimit());
    AssignedInstance edited =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(withLivenessProbe));

    assertTrue(AgentMain.requiresVesselReplacement(edited, existing));
  }

  @Test
  void an_unchanged_vessel_assignment_at_the_same_key_never_requires_replacement() {
    ModuleDescriptor descriptor = descriptor("gateway-vessel", IsolationTier.TIER_2);
    AssignedInstance assigned =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));
    SupervisedVessel existing = supervisedVessel(assigned);

    // A fresh AssignedInstance/VesselSpec with identical field values, not the same object --
    // this is what a real re-fetch of unchanged desired state actually looks like, and must not
    // be mistaken for drift on every poll.
    AssignedInstance sameAssignmentAgain =
        new AssignedInstance(
            "gateway-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));

    assertFalse(AgentMain.requiresVesselReplacement(sameAssignmentAgain, existing));
  }

  @Test
  void requires_replacement_for_module_hosting_ignores_vessel_and_is_unaffected() {
    // Guards against requiresVesselReplacement's vessel() comparison ever being merged into
    // requiresReplacement by mistake: a hosted module's runtime config comes entirely from its
    // artifact's own gimle-module.yaml, so a vessel() difference here must stay irrelevant to it.
    ModuleDescriptor v1 = descriptor("provider", IsolationTier.TIER_2);
    AssignedInstance assignedV1 = assignedInstance("provider-deployment", v1, Optional.empty());
    SupervisedInstance existing = supervisedInstance(assignedV1, v1, null);

    AssignedInstance assignedWithVessel =
        new AssignedInstance(
            "provider-deployment",
            0,
            v1.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));

    assertFalse(AgentMain.requiresReplacement(assignedWithVessel, existing));
  }

  // ---- reconcileVesselAssignment: a vessel that has genuinely exhausted VesselProcessSupervisor's
  // restart budget must actually stay given up on the very next poll tick, not be handed a fresh
  // supervisor and a fresh budget the moment reconcileAssignments next runs -- the M64 finding's
  // "exhausted its restart budget; giving up" ERROR log was directly contradicted by the agent
  // respawning the same instance roughly 15s later, over and over, because the old code removed
  // the exhausted SupervisedVessel outright, so the next tick simply read "never started" and
  // started over with a clean RestartTracker. ----

  @Test
  void an_exhausted_vessel_with_an_unchanged_assignment_is_not_handed_a_fresh_budget() {
    ModuleDescriptor descriptor = descriptor("echo-vessel", IsolationTier.TIER_2);
    AssignedInstance assigned =
        new AssignedInstance(
            "echo-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));
    SupervisedVessel exhausted = supervisedVessel(assigned);
    exhausted.restartBudgetExhausted = true;
    Map<String, SupervisedVessel> supervisedVessels = new LinkedHashMap<>();
    String key = "echo-deployment#0";
    supervisedVessels.put(key, exhausted);

    // A fresh AssignedInstance with identical field values -- what an ordinary re-fetch of
    // unchanged desired state looks like, exactly the shape reconcileAssignments calls this with
    // on every tick regardless of whether anything actually changed.
    AssignedInstance sameAssignmentAgain =
        new AssignedInstance(
            "echo-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("hello")));

    AgentMain.reconcileVesselAssignment(
        sameAssignmentAgain,
        key,
        supervisedVessels,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);

    // Still the exact same (dead) SupervisedVessel object: no new VesselProcessSupervisor, and
    // therefore no fresh RestartTracker, was ever created for this unchanged assignment.
    assertTrue(
        supervisedVessels.get(key) == exhausted,
        "an unchanged assignment must leave a give-up state alone rather than restart it");
  }

  @Test
  void a_changed_assignment_clears_an_exhausted_vessels_give_up_state() {
    // A real (never-started) supervisor rather than null: the replacement branch below calls
    // stopVesselInstance, which closes the existing instance's supervisor unconditionally.
    VesselProcessSupervisor neverStarted =
        new VesselProcessSupervisor(
            "echo-deployment#0",
            List.of("true"),
            Map.of(),
            Optional.empty(),
            new RestartTracker(
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10)),
            exhaustedKey -> {},
            Path.of("does-not-matter.log"),
            respawnedKey -> {});
    ModuleDescriptor descriptor = descriptor("echo-vessel", IsolationTier.TIER_2);
    AssignedInstance original =
        new AssignedInstance(
            "echo-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("v1")));
    SupervisedVessel exhausted =
        new SupervisedVessel(
            original,
            original.vessel().orElseThrow(),
            neverStarted,
            null,
            Map.of(),
            List.of(),
            Instant.now());
    exhausted.restartBudgetExhausted = true;
    Map<String, SupervisedVessel> supervisedVessels = new LinkedHashMap<>();
    String key = "echo-deployment#0";
    supervisedVessels.put(key, exhausted);

    AssignedInstance changed =
        new AssignedInstance(
            "echo-deployment",
            0,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty(),
            OptionalInt.empty(),
            Optional.of(vesselSpec("v2")));

    // The subsequent startVesselInstance call fails against these null collaborators (no real
    // artifact/resource limiter to spawn from) and is caught and logged, exactly like any other
    // start failure -- what this asserts is that the old give-up state does not survive that
    // attempt, not that a real process gets spawned (VesselProcessSupervisorTest already covers
    // real spawning end to end).
    AgentMain.reconcileVesselAssignment(
        changed, key, supervisedVessels, null, null, null, null, null, null, null, null);

    assertTrue(
        supervisedVessels.get(key) != exhausted,
        "a genuinely changed assignment must clear a prior give-up state rather than leave it"
            + " stuck forever");
  }

  // ---- instanceKey: tenant-scoped so two tenants' identically-named workload never collapse
  // onto the same supervised/instanceShippers/capacityTracker slot ----

  /**
   * QA finding: two StatefulSets sharing a name across two tenants at the same index were placed by
   * the control plane onto the same node, but this agent's own {@code supervised} map used to key
   * purely on {@code deploymentName#index} -- whichever tenant's assignment this agent processed
   * first "owned" that bare key, so the second tenant's identically-shaped assignment always read
   * as already-supervised and this agent never started a real worker for it, even after the first
   * tenant's workload was deleted and the key genuinely freed.
   */
  @Test
  void instance_key_is_scoped_by_tenant_not_just_deployment_name_and_index() {
    String acmeKey = AgentMain.instanceKey(Optional.of("acme"), "session-store", 0);
    String globexKey = AgentMain.instanceKey(Optional.of("globex"), "session-store", 0);
    String untenantedKey = AgentMain.instanceKey(Optional.empty(), "session-store", 0);

    assertFalse(acmeKey.equals(globexKey), "two different tenants must never share a key");
    assertFalse(acmeKey.equals(untenantedKey), "a real tenant must never collide with untenanted");
    assertEquals(
        acmeKey,
        AgentMain.instanceKey(Optional.of("acme"), "session-store", 0),
        "the same tenant/name/index must always resolve to the identical key");
  }

  // ---- findRenameSource / renameInPlace: surge promotion retargeting an already-running worker
  // instance in place, no restart -- see DeploymentReconciler#handleSurge's own promotion step ----

  /** The untenanted (matching every {@code AssignedInstance} built below) form of instanceKey(). */
  private static String key(String deploymentName, int instanceIndex) {
    return AgentMain.instanceKey(Optional.empty(), deploymentName, instanceIndex);
  }

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
    supervised.put(key("orders-service", 5), surgeInstance);

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
            Map.of());
    AssignedInstance staleAssigned = assignedInstance("orders-service", v1, Optional.empty());
    SupervisedInstance staleInstance = supervisedInstance(staleAssigned, v1, null);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put(key("orders-service", 5), staleInstance);

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
    supervised.put(key("orders-service", 5), instance);
    Map<String, List<MuninnShipper>> instanceShippers = new LinkedHashMap<>();
    instanceShippers.put(key("orders-service", 5), List.of());
    CapacityTracker capacityTracker = new CapacityTracker(1_000_000_000L, 4000L);
    capacityTracker.tryAssign(key("orders-service", 5), v2.resourceRequest());

    AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);
    AgentMain.renameInPlace(
        key("orders-service", 1), renamed, instance, supervised, instanceShippers, capacityTracker);

    assertFalse(supervised.containsKey(key("orders-service", 5)));
    assertEquals(instance, supervised.get(key("orders-service", 1)));
    assertEquals(renamed, instance.assigned);
    assertFalse(instanceShippers.containsKey(key("orders-service", 5)));
    assertTrue(instanceShippers.containsKey(key("orders-service", 1)));
    // The capacity reservation must have moved with the key, not stayed leaked under the old one
    // or been dropped -- see CapacityTracker#rekey's own dedicated test for the full behavior.
    assertTrue(capacityTracker.tryAssign(key("orders-service", 5), v2.resourceRequest()));
    assertThrows(
        IllegalStateException.class,
        () -> capacityTracker.tryAssign(key("orders-service", 1), v2.resourceRequest()));
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
            supervised.put(key("orders-service", 5), instance);
            Map<String, List<MuninnShipper>> instanceShippers = new LinkedHashMap<>();

            AssignedInstance renamed = renamedAssignment("orders-service", 1, v2, 5);
            AgentMain.renameInPlace(
                key("orders-service", 1),
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

  // ---- updateVesselHealth / evaluateProbe: regression coverage for a vessel declaring more
  // than one {port: ...} env entry -- the probe must dial the specific port its own manifest
  // config names, not whichever one happens to iterate first. ----

  private static HttpServer respondingHttpServer(int status, String path) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  /** A genuinely live, long-running process for {@code updateVesselHealth}'s own alive check. */
  private static VesselProcessSupervisor runningVesselProcessSupervisor(Path applicationLogFile)
      throws IOException {
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));
    VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "probe-port-test",
            List.of("sleep", "30"),
            Map.of(),
            Optional.empty(),
            tracker,
            id -> {},
            applicationLogFile,
            id -> {});
    supervisor.start();
    return supervisor;
  }

  @Test
  void a_readiness_probe_dials_the_port_it_names_not_whichever_declared_port_iterates_first(
      @TempDir Path tempDir) throws IOException {
    HttpServer healthy = respondingHttpServer(200, "/health");
    HttpServer unhealthy = respondingHttpServer(500, "/health");
    try (VesselProcessSupervisor supervisor =
        runningVesselProcessSupervisor(tempDir.resolve("vessel.log"))) {
      Map<String, VesselEnvValue> env =
          Map.of(
              "FIXED_PORT", new VesselEnvValue.PortAllocation(OptionalInt.of(9000)),
              "HTTP_PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty()));
      VesselProbeSpec readiness = new VesselProbeSpec.Http("/health", Optional.of("HTTP_PORT"), 0);
      VesselSpec vessel =
          new VesselSpec(
              List.of(),
              List.of(),
              env,
              List.of(),
              new VesselProbes(Optional.empty(), Optional.of(readiness)),
              REQUEST,
              LIMIT);
      Map<String, Integer> allocatedPorts =
          Map.of(
              "FIXED_PORT", unhealthy.getAddress().getPort(),
              "HTTP_PORT", healthy.getAddress().getPort());
      SupervisedVessel instance =
          new SupervisedVessel(
              null,
              vessel,
              supervisor,
              null,
              allocatedPorts,
              List.of(),
              Instant.now().minusSeconds(60));

      AgentMain.updateVesselHealth(instance, HttpClient.newHttpClient());

      assertEquals("ACTIVE", instance.lifecycleState);
    } finally {
      healthy.stop(0);
      unhealthy.stop(0);
    }
  }

  @Test
  void a_readiness_probe_naming_the_failing_port_stays_unready_even_though_the_other_port_is_up(
      @TempDir Path tempDir) throws IOException {
    HttpServer healthy = respondingHttpServer(200, "/health");
    HttpServer unhealthy = respondingHttpServer(500, "/health");
    try (VesselProcessSupervisor supervisor =
        runningVesselProcessSupervisor(tempDir.resolve("vessel.log"))) {
      Map<String, VesselEnvValue> env =
          Map.of(
              "FIXED_PORT", new VesselEnvValue.PortAllocation(OptionalInt.of(9000)),
              "HTTP_PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty()));
      // Names the port whose server returns 500 -- proving resolution honors the declared name
      // in both directions, not just whichever port happens to be healthy.
      VesselProbeSpec readiness = new VesselProbeSpec.Http("/health", Optional.of("FIXED_PORT"), 0);
      VesselSpec vessel =
          new VesselSpec(
              List.of(),
              List.of(),
              env,
              List.of(),
              new VesselProbes(Optional.empty(), Optional.of(readiness)),
              REQUEST,
              LIMIT);
      Map<String, Integer> allocatedPorts =
          Map.of(
              "FIXED_PORT", unhealthy.getAddress().getPort(),
              "HTTP_PORT", healthy.getAddress().getPort());
      SupervisedVessel instance =
          new SupervisedVessel(
              null,
              vessel,
              supervisor,
              null,
              allocatedPorts,
              List.of(),
              Instant.now().minusSeconds(60));

      AgentMain.updateVesselHealth(instance, HttpClient.newHttpClient());

      assertEquals("STARTING", instance.lifecycleState);
    } finally {
      healthy.stop(0);
      unhealthy.stop(0);
    }
  }
}
