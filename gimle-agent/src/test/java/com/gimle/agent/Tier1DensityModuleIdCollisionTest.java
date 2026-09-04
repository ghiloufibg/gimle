package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.gimle.testkit.Await;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for the M34 "wedged at INSTALLED forever, zero log output" symptom: a second Job
 * instance packed onto an already-running shared Tier 1 worker whose {@code ModuleId} (name,
 * version, and jar content) is identical to one still resident there from an earlier run -- exactly
 * what a recurring Job re-run produces once {@code AgentMain#stopInstance}'s bare {@code
 * StopModule} is nacked by a completed job (see {@link Tier1DensityJobCompletionIntegrationTest})
 * and leaves that {@code ModuleId} behind at {@code COMPLETED} instead of actually uninstalling it.
 * {@link com.gimle.module.resolve.ModuleRegistry#register} treats an identical-content re-install
 * as an idempotent no-op rather than resetting the module's state, so the later job's own {@code
 * ResolveModule} genuinely fails on the worker ({@code ModuleController#resolve} requires {@code
 * INSTALLED}, not {@code COMPLETED}) and comes back as a {@link ControlMessage.Nack}.
 *
 * <p>{@link ControlMessage.Nack} carries no {@code ModuleId}, so under Tier 1 density {@link
 * AgentMain#readLoop} previously had only the connection-owning {@code SupervisedInstance} to apply
 * it to -- the wrong one, since the owner here is the earlier, already-{@code COMPLETED} job, not
 * the new one whose command actually failed. The owner's own {@code lifecycleState} guard ({@code
 * "INSTALLED".equals(...)}) never matched (it is {@code COMPLETED}), so the nack was silently
 * swallowed and the real, new instance stayed at {@code INSTALLED} forever -- exactly the reported
 * symptom: a healthy shared worker, a stuck instance, and nothing anywhere naming it.
 */
class Tier1DensityModuleIdCollisionTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(60)
  void a_second_instance_of_the_same_module_id_is_marked_failed_not_wedged_at_installed()
      throws Exception {
    Path jobJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.density.collision.job {
                }
                """)
            .withDescriptor(
                """
                name: com.gimle.fixture.density.collision.job
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
                """)
            .build(tempDir, "collision-job.jar");
    ModuleDescriptor descriptor = ModuleArtifactReader.read(jobJar).descriptor();
    ModuleId moduleId = descriptor.id();

    Path socketPath = Files.createTempDirectory("gimle-agent-uds-").resolve("c.sock");
    List<String> baseCommand =
        List.of(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            "com.gimle.worker.WorkerMain",
            "test-node",
            "");
    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 3, Duration.ofMinutes(1));

    HttpClient httpClient = HttpClient.newHttpClient();
    URI unreachableBaseUrl = URI.create("http://127.0.0.1:1/");
    LocalDiskVolumeManager volumeManager =
        new LocalDiskVolumeManager(Files.createTempDirectory("gimle-agent-density-data-"));

    try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
      WorkerProcessSupervisor supervisor =
          new WorkerProcessSupervisor(
              "worker-density-collision-it",
              () -> baseCommand,
              socketPath,
              restartTracker,
              id -> {});
      try {
        supervisor.start();

        try (WorkerConnection connection = server.accept()) {
          ControlMessage hello = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Hello.class, hello);

          AssignedInstance firstAssigned =
              new AssignedInstance(
                  "first-job", 0, moduleId, jobJar.toAbsolutePath().toString(), Optional.empty());
          SupervisedInstance firstInstance =
              new SupervisedInstance(
                  firstAssigned, supervisor, server, descriptor, "first-job#0", null);
          firstInstance.connection = connection;

          AssignedInstance secondAssigned =
              new AssignedInstance(
                  "second-job", 1, moduleId, jobJar.toAbsolutePath().toString(), Optional.empty());
          // A packed sibling: same worker/connection as firstInstance, its own key.
          SupervisedInstance secondInstance =
              new SupervisedInstance(
                  secondAssigned, supervisor, server, descriptor, "first-job#0", null);
          secondInstance.connection = connection;

          Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
          supervised.put("first-job#0", firstInstance);
          supervised.put("second-job#1", secondInstance);

          Thread readerThread =
              Thread.ofVirtual()
                  .start(
                      () ->
                          AgentMain.readLoop(
                              firstInstance,
                              "first-job#0",
                              null,
                              new ServiceCatalog(),
                              httpClient,
                              unreachableBaseUrl,
                              "node-a",
                              supervised,
                              null,
                              new ConcurrentHashMap<>()));
          try {
            // The first job runs to completion -- the same leaked-on-StopModule terminal state
            // Tier1DensityJobCompletionIntegrationTest confirms is left behind on the worker.
            AgentMain.sendInstallStartSequence(
                firstInstance,
                "first-job#0",
                connection,
                httpClient,
                unreachableBaseUrl,
                null,
                volumeManager);
            Await.until(
                () -> "COMPLETED".equals(firstInstance.lifecycleState), Duration.ofSeconds(20));
            // Mirrors AgentMain#stopInstance's own bookkeeping once the control plane observes
            // COMPLETED and removes the JobRun: the agent evicts its own supervised-map entry even
            // though the worker itself never actually uninstalled the module (its StopModule is
            // nacked -- see Tier1DensityJobCompletionIntegrationTest), which is exactly what makes
            // the collision below possible.
            supervised.remove("first-job#0");

            // A second instance of the *identical* module (same name, version, and jar content)
            // now joins the same worker -- exactly what AgentMain#installIntoExistingWorker does
            // once findReusableTier1Worker offers this worker up again for a repeat Job run.
            AgentMain.sendInstallStartSequence(
                secondInstance,
                "second-job#1",
                connection,
                httpClient,
                unreachableBaseUrl,
                null,
                volumeManager);

            // Before the fix, this instance stayed at "INSTALLED" forever: the worker's own
            // ResolveModule Nack (COMPLETED is not a legal state to resolve from) was applied to
            // `firstInstance`, whose own lifecycleState guard never matched, so nothing happened.
            Await.until(
                () -> "FAILED".equals(secondInstance.lifecycleState),
                Duration.ofSeconds(20),
                "second instance should be reported FAILED, not wedged at INSTALLED, once its"
                    + " install sequence collides with the first job's still-resident ModuleId");

            // The first (already-completed) instance must never be clobbered by the second's own
            // failure -- proof the fix routes the nack to the right instance, not just any.
            assertEquals("COMPLETED", firstInstance.lifecycleState);
          } finally {
            readerThread.interrupt();
          }
        }
      } finally {
        supervisor.close();
      }
    }
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
