package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Proves the protocol-level mechanics {@link AgentMain#installIntoExistingWorker} relies on: a real
 * {@code gimle-worker} subprocess can host two independently-installed modules over one control
 * channel connection, each reaching {@code ACTIVE} on its own, and tearing one down ({@code
 * StopModule}) leaves the other running in the same process. This is the real-process counterpart
 * to {@code AgentMainTest}'s {@code findReusableTier1Worker} unit tests, which cover the reuse
 * *decision* rather than what happens once a worker is actually shared -- same "hand-drive the
 * protocol directly, no HTTP control plane needed" shape as {@link AgentWorkerIntegrationTest},
 * extended to two modules over the one connection instead of one.
 */
class Tier1DensityIntegrationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void two_modules_share_one_worker_process_and_survive_one_being_stopped() throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(60), this::runScenario);
  }

  private void runScenario() throws Exception {
    Path providerJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.density.provider {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.density.provider", "1.0.0"))
            .build(tempDir, "provider.jar");
    Path consumerJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.density.consumer {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.density.consumer", "1.0.0"))
            .build(tempDir, "consumer.jar");

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
              "worker-density-it", () -> baseCommand, socketPath, restartTracker, id -> {});
      try {
        supervisor.start();

        try (WorkerConnection connection = server.accept()) {
          ControlMessage hello = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Hello.class, hello);
          long pid = ((ControlMessage.Hello) hello).pid();

          // Install and start the first module over this connection -- the same shape
          // AgentMain#driveInstanceUp uses for a fresh worker.
          ModuleId providerId = installAndStart(connection, providerJar, "corr-provider");

          // A second module joins the *same already-open* connection -- exactly what
          // AgentMain#installIntoExistingWorker does for Tier 1 density, driven here directly
          // rather than through the agent, matching AgentWorkerIntegrationTest's own "drive the
          // protocol by hand" style.
          ModuleId consumerId = installAndStart(connection, consumerJar, "corr-consumer");

          assertNotEquals(providerId, consumerId, "the two modules must be distinct");

          // Stopping the provider must not affect the consumer -- both are hosted in the same
          // worker process (same pid, confirmed above), and only the provider's own module
          // should tear down.
          connection.send(new ControlMessage.StopModule("corr-stop-provider", providerId));
          List<ControlMessage> stopMessages = receiveUntilAck(connection, "corr-stop-provider");
          assertEquals(
              List.of("STOPPING", "UNINSTALLED"),
              stateChangesFor(stopMessages, providerId),
              "stopMessages=" + stopMessages);
          // The consumer must not have been touched by the provider's teardown.
          assertEquals(List.of(), stateChangesFor(stopMessages, consumerId));

          // The worker process itself (and the consumer module within it) is still alive and
          // responsive -- a Ping/Pong round trip over the same, still-open connection proves the
          // process was never killed (a restart would have closed this connection outright and
          // this read would fail with IOException, not just time out).
          connection.send(new ControlMessage.Ping("corr-ping"));
          ControlMessage pong = connection.receive().orElseThrow();
          assertInstanceOf(ControlMessage.Pong.class, pong);
          assertEquals("corr-ping", ((ControlMessage.Pong) pong).correlationId());
          assertTrue(pid > 0, "hello must have reported a real pid");
        }
      } finally {
        supervisor.close();
      }
    }
  }

  private static ModuleId installAndStart(
      WorkerConnection connection, Path jar, String correlationPrefix) throws IOException {
    connection.send(
        new ControlMessage.InstallModule(
            correlationPrefix + "-install", jar.toAbsolutePath().toString()));
    List<ControlMessage> installMessages =
        receiveUntilAck(connection, correlationPrefix + "-install");
    ModuleId id = extractModuleIdFromStateChange(installMessages, "INSTALLED");

    connection.send(new ControlMessage.ResolveModule(correlationPrefix + "-resolve", id));
    List<ControlMessage> resolveMessages =
        receiveUntilAck(connection, correlationPrefix + "-resolve");
    assertEquals(
        List.of("RESOLVED"),
        stateChangesFor(resolveMessages, id),
        "resolveMessages=" + resolveMessages);

    connection.send(new ControlMessage.StartModule(correlationPrefix + "-start", id));
    List<ControlMessage> startMessages = receiveUntilAck(connection, correlationPrefix + "-start");
    assertEquals(
        List.of("STARTING", "ACTIVE"),
        stateChangesFor(startMessages, id),
        "startMessages=" + startMessages);
    return id;
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
