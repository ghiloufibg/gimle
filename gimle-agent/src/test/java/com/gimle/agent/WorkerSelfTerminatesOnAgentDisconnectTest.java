package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real {@code gimle-worker} subprocess, spawned exactly like {@link AgentWorkerIntegrationTest},
 * proving it exits on its own once its control channel to the agent closes -- the fix for the
 * orphaned-worker-JVM bug a real-cluster QA pass surfaced (hard-killing the node agent left its
 * child worker JVMs running forever, invisible to {@code hilmir}'s own run ledger, since nothing
 * told them their agent was gone).
 *
 * <p>Closing the agent-side {@link WorkerConnection} from this test -- without ever spawning or
 * killing a real agent process -- faithfully simulates every way an agent can disappear: a graceful
 * exit, {@code kill -9}, an OOM-kill, a host crash. All of them are indistinguishable from the
 * worker's own socket, which sees the identical EOF regardless of how its peer's file descriptors
 * got closed; the fix (and this test) don't depend on which one actually happened.
 *
 * <p>The installed module's own {@code onStart} hook deliberately leaves a plain, non-daemon {@link
 * Thread} running -- exactly the kind of thing completely ordinary hosted-module code does (a
 * thread pool, an embedded server) with zero special casing. Without that thread, this scenario
 * doesn't actually reproduce the bug: a bare worker JVM with no hosted module has nothing else
 * keeping it alive, so it happens to exit on its own the moment {@code main()} returns, fix or no
 * fix. The bug -- and what this test guards against regressing -- only shows up once a hosted
 * module has left something running behind, which is the realistic case a real deployment hits.
 */
class WorkerSelfTerminatesOnAgentDisconnectTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void
      a_worker_hosting_a_module_with_a_live_non_daemon_thread_still_exits_once_the_control_channel_to_its_agent_closes()
          throws Exception {
    Path jar = buildOrphanProneModuleJar();
    Path socketPath = Files.createTempDirectory("gimle-agent-uds-").resolve("c.sock");
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    // WorkerMain's third argument is tenantId-or-empty; this scenario doesn't exercise tenancy, so
    // it's blank -- WorkerProcessSupervisor appends the control-socket path itself as the final
    // argument, the same convention AgentWorkerIntegrationTest already establishes.
    List<String> baseCommand =
        List.of(javaExecutable, "-cp", classpath, "com.gimle.worker.WorkerMain", "test-node", "");
    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 3, Duration.ofMinutes(1));

    try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
      WorkerProcessSupervisor supervisor =
          new WorkerProcessSupervisor(
              "worker-disconnect-it", () -> baseCommand, socketPath, restartTracker, id -> {});
      try {
        supervisor.start();
        Process workerProcess;

        try (WorkerConnection connection = server.accept()) {
          ControlMessage hello = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Hello.class, hello);
          workerProcess = supervisor.process();

          connection.send(
              new ControlMessage.InstallModule("corr-install", jar.toAbsolutePath().toString()));
          List<ControlMessage> installMessages = receiveUntilAck(connection, "corr-install");
          ModuleInstanceId id = extractModuleIdFromStateChange(installMessages, "INSTALLED");

          connection.send(new ControlMessage.ResolveModule("corr-resolve", id));
          receiveUntilAck(connection, "corr-resolve");

          // Reaching ACTIVE runs the module's onStart hook, which starts the non-daemon thread
          // this scenario depends on -- deliberately never stopped/uninstalled below, the same way
          // a real agent that gets killed never gets to run any graceful-shutdown code either.
          connection.send(new ControlMessage.StartModule("corr-start", id));
          List<ControlMessage> startMessages = receiveUntilAck(connection, "corr-start");
          assertEquals(
              List.of("STARTING", "ACTIVE"),
              stateChangesFor(startMessages, id),
              "startMessages=" + startMessages);
        } // the try-with-resources close() above severs the control channel, standing in for the
        // agent process disappearing by any means -- this is the only trigger under test.

        boolean exited = workerProcess.waitFor(10, TimeUnit.SECONDS);
        assertTrue(
            exited,
            "worker did not exit on its own within 10s of its control channel closing, despite "
                + "its hosted module's own non-daemon thread still running");
        assertEquals(
            0,
            workerProcess.exitValue(),
            "worker should exit cleanly (System.exit(0)), not crash, once its agent is gone");
      } finally {
        supervisor.close();
      }
    }
  }

  private Path buildOrphanProneModuleJar() {
    List<Path> compileJars = findPlatformJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.fixture.orphan {
              requires static com.gimle.module;
              exports com.gimle.fixture.orphan;
            }
            """)
        .withClass(
            "com.gimle.fixture.orphan.OrphanProneHooks",
            """
            package com.gimle.fixture.orphan;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class OrphanProneHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                // Deliberately a plain, non-daemon Thread -- exactly what a thread pool or an
                // embedded server started by completely ordinary hosted-module code would leave
                // running, with no special casing needed to reproduce the orphan condition.
                Thread nonDaemon = new Thread(() -> {
                  try {
                    Thread.sleep(Long.MAX_VALUE);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }, "simulated-hosted-module-work");
                nonDaemon.start();
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.fixture.orphan
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
              hooks: com.gimle.fixture.orphan.OrphanProneHooks
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "orphan-prone.jar");
  }

  /**
   * Same needle-matching approach {@code RealBundledHookAndProbeInvocationTest} already
   * establishes: {@code requires static com.gimle.module} only needs {@code gimle-module}/{@code
   * gimle-core} on the compiler's module path, which this test's own classpath already carries
   * either as installed jars or (when the reactor builds gimle-agent/gimle-module together)
   * exploded {@code target/classes} directories.
   */
  private static List<Path> findPlatformJars() {
    String[] moduleArtifacts = {"gimle-module", "gimle-core"};
    List<Path> result = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    for (String entry : cp.split(File.pathSeparator)) {
      String fileName = Path.of(entry).getFileName().toString();
      String normalized = entry.replace('\\', '/');
      for (String artifact : moduleArtifacts) {
        boolean installedJar = fileName.startsWith(artifact + "-") && fileName.endsWith(".jar");
        boolean reactorClasses =
            normalized.endsWith("/" + artifact + "/target/classes")
                || normalized.endsWith("/" + artifact + "/target/test-classes");
        if ((installedJar && !fileName.contains("tests")) || reactorClasses) {
          result.add(Path.of(entry));
        }
      }
    }
    return result;
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

  private static List<String> stateChangesFor(List<ControlMessage> messages, ModuleInstanceId id) {
    return messages.stream()
        .filter(
            m -> m instanceof ControlMessage.ModuleStateChanged changed && changed.id().equals(id))
        .map(m -> ((ControlMessage.ModuleStateChanged) m).state())
        .toList();
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
