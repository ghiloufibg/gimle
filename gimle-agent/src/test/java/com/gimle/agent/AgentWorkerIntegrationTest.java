package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
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
 * The design's mandatory Phase 2 integration test (§9, test plan): a real agent-side {@link
 * ControlChannelServer}/{@link WorkerProcessSupervisor} pair spawning a real {@code gimle-worker}
 * subprocess (not an in-process fake), installing a module over the actual control channel and
 * observing it reach {@code ACTIVE}. Runs natively and identically on whatever OS executes the
 * suite -- no Docker, no Linux-only gate, no skip path -- because nothing here is platform-gated.
 *
 * <p>The worker subprocess is launched with this test JVM's own classpath ({@code
 * java.class.path}), which already carries the fully-resolved {@code gimle-worker} runtime and
 * every transitive dependency (added deliberately as test-scope dependencies of {@code gimle-agent}
 * for exactly this purpose) -- the same "spawn a real subprocess" pattern {@code gimle-module}'s
 * {@code RedeployLoopFlatMetaspaceTest} already established, just reusing the ambient classpath
 * instead of hand-assembling one from anchor classes, since here the goal is "everything the worker
 * needs," not a deliberately minimal set.
 */
class AgentWorkerIntegrationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel()
      throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(60), this::runScenario);
  }

  private void runScenario() throws Exception {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.agentworker {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.agentworker", "1.0.0"))
            .build(tempDir, "fixture.jar");

    Path socketPath = Files.createTempDirectory("gimle-agent-uds-").resolve("c.sock");
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    List<String> baseCommand =
        List.of(javaExecutable, "-cp", classpath, "com.gimle.worker.WorkerMain", "test-node");
    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 3, Duration.ofMinutes(1));

    try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
      WorkerProcessSupervisor supervisor =
          new WorkerProcessSupervisor(
              "worker-it", baseCommand, socketPath, restartTracker, id -> {});
      try {
        supervisor.start();

        try (WorkerConnection connection = server.accept()) {
          ControlMessage hello = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Hello.class, hello);

          connection.send(
              new ControlMessage.InstallModule("corr-install", jar.toAbsolutePath().toString()));
          List<ControlMessage> installMessages = receiveUntilAck(connection, "corr-install");
          ModuleId id = extractModuleIdFromStateChange(installMessages, "INSTALLED");

          connection.send(new ControlMessage.ResolveModule("corr-resolve", id));
          List<ControlMessage> resolveMessages = receiveUntilAck(connection, "corr-resolve");
          assertEquals(
              List.of("RESOLVED"),
              stateChangesFor(resolveMessages, id),
              "resolveMessages=" + resolveMessages);

          connection.send(new ControlMessage.StartModule("corr-start", id));
          List<ControlMessage> startMessages = receiveUntilAck(connection, "corr-start");
          assertEquals(
              List.of("STARTING", "ACTIVE"),
              stateChangesFor(startMessages, id),
              "startMessages=" + startMessages);

          // stop(), not uninstall(): ModuleController#uninstall() rejects an ACTIVE module
          // outright (only stop() drives ACTIVE -> STOPPING -> UNINSTALLED in one call).
          connection.send(new ControlMessage.StopModule("corr-stop", id));
          List<ControlMessage> stopMessages = receiveUntilAck(connection, "corr-stop");
          assertEquals(
              List.of("STOPPING", "UNINSTALLED"),
              stateChangesFor(stopMessages, id),
              "stopMessages=" + stopMessages);
        }
      } finally {
        supervisor.close();
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

  private static ModuleId extractModuleIdFromStateChange(
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

  private static List<String> stateChangesFor(List<ControlMessage> messages, ModuleId id) {
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
