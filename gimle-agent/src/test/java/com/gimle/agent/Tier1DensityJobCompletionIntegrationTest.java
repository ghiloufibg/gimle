package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Investigated as part of M34: a Job placed into an already-running shared (Tier 1) worker
 * alongside a Job that already ran to completion on that same worker -- the exact shape a real
 * cluster produces once {@code AgentMain#stopInstance} tears down a finished job's {@code
 * SupervisedInstance}.
 *
 * <p>{@code stopInstance} always sends {@code StopModule} to tear an instance down, on the
 * assumption (true for every other workload kind) that the module is still {@code ACTIVE}. A Job
 * that already reached its own {@code COMPLETED} terminal state is not: {@code
 * ModuleController#stop} requires {@code ACTIVE}/{@code STOPPING} and throws for {@code COMPLETED},
 * which the worker reports back as a {@code Nack} rather than ever actually uninstalling the module
 * -- so the completed job's {@code ModuleId}, its {@code ModuleLayer}, and every {@code
 * WorkerRuntime} bookkeeping entry for it are never cleaned up and stay resident in the shared
 * worker process forever. This is a real, reproducible leak (confirmed by the first assertion
 * below, a separate finding from M34 worth its own follow-up), but not the mechanism behind M34
 * itself: the second half of this test packs a genuinely new, unrelated Job onto that same worker
 * afterward, and it installs, starts, and completes normally -- the leaked completed job does not
 * block it.
 */
class Tier1DensityJobCompletionIntegrationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void a_completed_jobs_stop_module_is_nacked_and_a_later_sibling_still_installs_cleanly()
      throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(60), this::runScenario);
  }

  private void runScenario() throws Exception {
    Path jobJar = jobFixtureJar("com.gimle.fixture.density.job.first");
    Path laterJar = jobFixtureJar("com.gimle.fixture.density.job.second");

    Path socketPath = Files.createTempDirectory("gimle-agent-uds-").resolve("c.sock");
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    List<String> baseCommand =
        List.of(javaExecutable, "-cp", classpath, "com.gimle.worker.WorkerMain", "test-node", "");
    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 3, Duration.ofMinutes(1));

    try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
      WorkerProcessSupervisor supervisor =
          new WorkerProcessSupervisor(
              "worker-density-job-it", () -> baseCommand, socketPath, restartTracker, id -> {});
      try {
        supervisor.start();

        try (WorkerConnection connection = server.accept()) {
          ControlMessage hello = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Hello.class, hello);

          // A real Job assignment: deploymentName/instanceIndex carry jobName/attempt, exactly
          // as ApiServer#handleAssignments maps a JobRun -- installAndStartJob below registers
          // the InstanceIdentity that entails, the same as the real agent does.
          ModuleInstanceId firstJobId =
              installAndStartJob(connection, jobJar, "first-job", "corr-first", 0);
          awaitCompleted(connection, firstJobId);

          // Mirrors AgentMain#stopInstance exactly: once the control plane observes COMPLETED
          // and removes the JobRun, the agent's next reconcile tick sees this key no longer
          // assigned and tears it down with a bare StopModule -- never UninstallModule.
          connection.send(new ControlMessage.StopModule("corr-first-stop", firstJobId));
          ControlMessage stopReply = receiveNextNonEventMessage(connection);
          assertInstanceOf(
              ControlMessage.Nack.class,
              stopReply,
              "ModuleController#stop requires ACTIVE/STOPPING -- a COMPLETED job's StopModule"
                  + " must be nacked, not silently accepted, confirming the module was never"
                  + " actually uninstalled on the worker");

          // A second, unrelated Job now joins the very same connection/worker -- exactly what
          // AgentMain#installIntoExistingWorker does once findReusableTier1Worker offers this
          // worker up again. If the leaked first job were the mechanism behind M34, this
          // install/resolve/start sequence would never complete.
          ModuleInstanceId secondJobId =
              installAndStartJob(connection, laterJar, "second-job", "corr-second", 1);
          awaitCompleted(connection, secondJobId);
        }
      } finally {
        supervisor.close();
      }
    }
  }

  private Path jobFixtureJar(String moduleName) throws IOException {
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(moduleName))
        .withDescriptor(
            """
            name: %s
            version: 1.0.0
            isolation:
              tier: TIER_1
            resources:
              request:
                memory: 16Mi
                cpu: 10m
              limit:
                memory: 32Mi
                cpu: 50m
            lifecycle:
              jobHooks: com.gimle.agent.testsupport.InstantSucceedingJobHooks
            """
                .formatted(moduleName))
        .build(tempDir, moduleName + ".jar");
  }

  private static ModuleInstanceId installAndStartJob(
      WorkerConnection connection,
      Path jar,
      String deploymentName,
      String correlationPrefix,
      int instanceIndex)
      throws IOException {
    connection.send(
        new ControlMessage.InstallModule(
            correlationPrefix + "-install",
            jar.toAbsolutePath().toString(),
            deploymentName,
            instanceIndex));
    List<ControlMessage> installMessages =
        receiveUntilAck(connection, correlationPrefix + "-install");
    ModuleInstanceId id = extractModuleIdFromStateChange(installMessages, "INSTALLED");

    connection.send(new ControlMessage.ResolveModule(correlationPrefix + "-resolve", id));
    receiveUntilAck(connection, correlationPrefix + "-resolve");

    connection.send(new ControlMessage.StartModule(correlationPrefix + "-start", id));
    receiveUntilAck(connection, correlationPrefix + "-start");
    return id;
  }

  /** Waits for the {@code COMPLETED} transition a job's own hook run drives, on its own thread. */
  private static void awaitCompleted(WorkerConnection connection, ModuleInstanceId id)
      throws IOException {
    while (true) {
      ControlMessage message = receiveNextNonEventMessage(connection);
      if (message instanceof ControlMessage.ModuleStateChanged changed
          && changed.id().equals(id)
          && "COMPLETED".equals(changed.state())) {
        return;
      }
    }
  }

  /**
   * {@code InstanceEventOccurred} rides the same channel as every other message once an {@link
   * ControlMessage.InstallModule} carries a real deployment name -- skipped here rather than
   * threaded through every caller, since none of this test's assertions care about it.
   */
  private static ControlMessage receiveNextNonEventMessage(WorkerConnection connection)
      throws IOException {
    while (true) {
      ControlMessage message = connection.receive().orElseThrow();
      if (!(message instanceof ControlMessage.InstanceEventOccurred)) {
        return message;
      }
    }
  }

  private static List<ControlMessage> receiveUntilAck(
      WorkerConnection connection, String correlationId) throws IOException {
    List<ControlMessage> messages = new ArrayList<>();
    while (true) {
      Optional<ControlMessage> received = connection.receive();
      if (received.isEmpty()) {
        fail(
            "worker closed the control channel before acking "
                + correlationId
                + "; saw "
                + messages);
      }
      ControlMessage message = received.get();
      messages.add(message);
      if (message instanceof ControlMessage.Ack ack && ack.correlationId().equals(correlationId)) {
        return messages;
      }
      if (message instanceof ControlMessage.Nack nack
          && nack.correlationId().equals(correlationId)) {
        fail("worker nacked " + correlationId + ": " + nack.reason());
      }
    }
  }

  private static ModuleInstanceId extractModuleIdFromStateChange(
      List<ControlMessage> messages, String state) {
    return messages.stream()
        .filter(
            m ->
                m instanceof ControlMessage.ModuleStateChanged changed
                    && changed.state().equals(state))
        .map(m -> ((ControlMessage.ModuleStateChanged) m).id())
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + state + " state change in " + messages));
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
