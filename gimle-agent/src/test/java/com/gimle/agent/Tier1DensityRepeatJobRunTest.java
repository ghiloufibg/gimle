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
 * A recurring Job's second run, packed onto the shared Tier 1 worker its first run is still
 * resident on. This used to wedge: {@code AgentMain#stopInstance} sends a bare {@code StopModule}
 * that a completed job nacks (see {@link Tier1DensityJobCompletionIntegrationTest}), leaving the
 * first run behind at {@code COMPLETED}; the worker then keyed its registry by module coordinate,
 * so the second run's install read as an idempotent re-install of that leftover and its {@code
 * ResolveModule} genuinely failed ({@code ModuleController#resolve} requires {@code INSTALLED}, not
 * {@code COMPLETED}).
 *
 * <p>Keying the worker by instance removes the collision at its source: the second run is a
 * different instance of the same artifact, so it installs into its own state and starts normally
 * alongside the leftover. What this test still holds onto is that the two are genuinely independent
 * -- the second reaching {@code ACTIVE} must not disturb the first's terminal state, which is the
 * same demux {@link ControlMessage.Nack} needs, since a nack carries no id of its own and can only
 * be applied to whichever sibling's command actually failed.
 */
class Tier1DensityRepeatJobRunTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(60)
  void a_repeat_job_run_starts_alongside_the_leftover_it_used_to_collide_with() throws Exception {
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

            // The repeat run joins the same worker -- exactly what
            // AgentMain#installIntoExistingWorker does once findReusableTier1Worker offers this
            // worker up again.
            AgentMain.sendInstallStartSequence(
                secondInstance,
                "second-job#1",
                connection,
                httpClient,
                unreachableBaseUrl,
                null,
                volumeManager);

            // COMPLETED, not ACTIVE: this fixture's job hooks run to completion, so a repeat run
            // that installs and starts cleanly passes straight through ACTIVE to its own terminal
            // state -- the same run the first one made.
            Await.until(
                () -> "COMPLETED".equals(secondInstance.lifecycleState),
                Duration.ofSeconds(20),
                "the repeat run is its own instance, so it installs and runs rather than"
                    + " colliding with the first run's leftover state");

            // The first run's terminal state must survive its sibling starting up in the same
            // worker -- proof each instance's own reports reach only itself.
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
