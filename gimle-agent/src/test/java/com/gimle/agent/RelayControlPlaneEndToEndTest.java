package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real hosted module, running inside a real spawned {@code gimle-worker} subprocess (not an
 * in-process fake -- the same "not a Docker/subprocess shortcut" posture {@code
 * AgentWorkerIntegrationTest} already established), genuinely round-trips a {@code
 * ctx.relayControlPlaneRead(...)} call through the real worker-agent control channel: the worker
 * process's own {@code ControlPlaneRelay}, a real {@link WorkerConnection}/{@link
 * ControlChannelServer} socket pair, and this test playing {@code AgentMain}'s own role on the
 * receiving end (the "fake/stub agent-side handler" substitute for a full second {@code AgentMain}
 * subprocess -- {@link AgentRelayControlPlaneReadTest} already separately proves {@code
 * AgentMain.readLoop}'s own whitelist-and-real-HTTP-call handling against a real stub control
 * plane, so this test's own responder only needs to prove the plumbing on the worker's side of that
 * boundary, not re-prove the agent's).
 *
 * <p>The relay call is made from a virtual thread the hook spawns, deliberately not from {@code
 * onStart} itself: {@code onStart} runs synchronously on {@code WorkerMain}'s own single receive-
 * loop thread ({@code runCommand}), which is the same thread that would need to read the eventual
 * {@code RelayControlPlaneResult} back off the wire -- calling a blocking relay request from there
 * would deadlock the worker against itself. This is exactly the {@code
 * ModuleContext#relayControlPlaneRead} javadoc's "an arbitrary calling thread (a virtual thread
 * handling a real inbound gateway request)" caller shape, not a lifecycle hook's own thread.
 */
class RelayControlPlaneEndToEndTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void a_hosted_modules_relay_call_round_trips_through_a_real_worker_process() throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(60), this::runScenario);
  }

  private void runScenario() throws Exception {
    List<Path> compileJars = findPlatformJars();
    Path dataDirectory = tempDir.resolve("data");
    Files.createDirectories(dataDirectory);

    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.relay {
                  requires static com.gimle.module;
                  exports com.gimle.fixture.relay;
                }
                """)
            .withClass(
                "com.gimle.fixture.relay.RelayHooks",
                """
                package com.gimle.fixture.relay;
                import com.gimle.module.lifecycle.ModuleContext;
                import com.gimle.module.lifecycle.ModuleLifecycleHooks;
                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class RelayHooks implements ModuleLifecycleHooks {
                  public void onInstall(ModuleContext ctx) {}
                  public void onStart(ModuleContext ctx) {
                    Path dir = ctx.dataDirectory().orElseThrow();
                    Thread.ofVirtual().start(() -> {
                      ModuleContext.RelayResult result =
                          ctx.relayControlPlaneRead("/endpoints/greeter");
                      try {
                        Files.writeString(
                            dir.resolve("relay-result.txt"),
                            result.status() + "|" + result.body());
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
                  }
                  public void onStop(ModuleContext ctx) {}
                  public void onUninstall(ModuleContext ctx) {}
                }
                """)
            .withDescriptor(
                """
                name: com.gimle.fixture.relay
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
                  hooks: com.gimle.fixture.relay.RelayHooks
                """)
            .dependsOn(compileJars.toArray(Path[]::new))
            .build(tempDir, "relay-fixture.jar");

    Path socketPath = Files.createTempDirectory("gimle-agent-relay-e2e-uds-").resolve("c.sock");
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
              "worker-relay-e2e", () -> baseCommand, socketPath, restartTracker, id -> {});
      try {
        supervisor.start();

        try (WorkerConnection connection = server.accept()) {
          // A single background reader owns every receive() call on this connection from here on
          // -- the real WorkerConnection isn't safe for two threads to read from concurrently, so
          // nothing else on this side may call connection.receive() directly once the stub agent
          // starts, Hello included.
          StubAgent stubAgent = StubAgent.start(connection);
          try {
            assertInstanceOf(ControlMessage.Hello.class, stubAgent.awaitHello());

            ModuleId id =
                extractModuleIdFromStateChange(
                    stubAgent.sendAndAwaitAck(
                        new ControlMessage.InstallModule(
                            "corr-install", jar.toAbsolutePath().toString()),
                        "corr-install"),
                    "INSTALLED");

            stubAgent.sendAndAwaitAck(
                new ControlMessage.ResolveModule(
                    "corr-resolve", id, dataDirectory.toAbsolutePath().toString()),
                "corr-resolve");

            stubAgent.sendAndAwaitAck(
                new ControlMessage.StartModule("corr-start", id), "corr-start");

            // The relay call happens on the hook's own spawned thread, not synchronously within
            // onStart -- so it may still be in flight even after StartModule's own Ack arrives.
            // Polling for the result file (rather than another correlation-keyed wait) is the
            // honest reflection of that: nothing in this wire protocol tells the agent when a
            // module's own background thread finishes an unrelated relay call.
            Path resultFile = dataDirectory.resolve("relay-result.txt");
            awaitFile(resultFile, Duration.ofSeconds(20));
            assertEquals("200|[{\"nodeId\":\"node-a\"}]", Files.readString(resultFile));
            assertEquals(
                List.of("/endpoints/greeter"),
                stubAgent.receivedRelayPaths,
                "the agent must see exactly the path the module asked for");

            stubAgent.sendAndAwaitAck(new ControlMessage.StopModule("corr-stop", id), "corr-stop");
          } finally {
            stubAgent.stop();
          }
        }
      } finally {
        supervisor.close();
      }
    }
  }

  private static void awaitFile(Path path, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (!Files.exists(path)) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("relay result file never appeared: " + path);
      }
      Thread.sleep(50);
    }
  }

  /**
   * The minimal hand-rolled stand-in for {@code AgentMain}'s own dispatch this test needs: one
   * background reader satisfying {@code Ack}/{@code Nack} waits by correlation id (the same
   * correlation-keyed-future shape {@code ControlPlaneRelay} itself uses on the worker side) and
   * answering every {@code RelayControlPlaneRead} it sees with a canned {@code
   * RelayControlPlaneResult} -- standing in for the real control-plane HTTP call {@code
   * AgentMain#handleRelayRead} makes, already covered separately by {@link
   * AgentRelayControlPlaneReadTest}.
   */
  private static final class StubAgent {
    private final WorkerConnection connection;
    private Thread readerThread;
    private final Map<String, CompletableFuture<List<ControlMessage>>> pendingAcks =
        new ConcurrentHashMap<>();
    private final CompletableFuture<ControlMessage.Hello> helloFuture = new CompletableFuture<>();
    final List<String> receivedRelayPaths = new CopyOnWriteArrayList<>();
    private final List<ControlMessage> buffered = new CopyOnWriteArrayList<>();

    private StubAgent(WorkerConnection connection) {
      this.connection = connection;
    }

    static StubAgent start(WorkerConnection connection) {
      StubAgent agent = new StubAgent(connection);
      agent.readerThread = Thread.ofVirtual().start(agent::readLoop);
      return agent;
    }

    private void readLoop() {
      try {
        Optional<ControlMessage> received;
        while ((received = connection.receive()).isPresent()) {
          ControlMessage message = received.get();
          if (message instanceof ControlMessage.Hello hello) {
            helloFuture.complete(hello);
            continue;
          }
          if (message instanceof ControlMessage.RelayControlPlaneRead request) {
            receivedRelayPaths.add(request.path());
            connection.send(
                new ControlMessage.RelayControlPlaneResult(
                    request.correlationId(), 200, "[{\"nodeId\":\"node-a\"}]"));
            continue;
          }
          buffered.add(message);
          String correlationId = correlationIdOf(message);
          if (correlationId == null) {
            continue;
          }
          CompletableFuture<List<ControlMessage>> waiter = pendingAcks.get(correlationId);
          if (waiter != null
              && (message instanceof ControlMessage.Ack
                  || message instanceof ControlMessage.Nack)) {
            waiter.complete(List.copyOf(buffered));
            buffered.clear();
          }
        }
      } catch (IOException e) {
        // Connection closed underneath this reader -- test tearing down, not a real failure.
      }
    }

    ControlMessage.Hello awaitHello() throws Exception {
      return helloFuture.get(20, TimeUnit.SECONDS);
    }

    /**
     * Registers the correlation-keyed wait <em>before</em> sending {@code command} -- the same
     * register-then-send ordering {@link ControlPlaneRelay#request} itself uses, avoiding the race
     * where a fast worker replies before this side ever starts listening for it. Returns every
     * message received since the previous send, up to and including this command's own {@code Ack}.
     */
    List<ControlMessage> sendAndAwaitAck(ControlMessage command, String correlationId)
        throws Exception {
      CompletableFuture<List<ControlMessage>> future = new CompletableFuture<>();
      pendingAcks.put(correlationId, future);
      connection.send(command);
      List<ControlMessage> messages = future.get(20, TimeUnit.SECONDS);
      ControlMessage last = messages.get(messages.size() - 1);
      if (last instanceof ControlMessage.Nack nack) {
        fail("worker nacked " + correlationId + ": " + nack.reason());
      }
      return messages;
    }

    private static String correlationIdOf(ControlMessage message) {
      return switch (message) {
        case ControlMessage.Ack m -> m.correlationId();
        case ControlMessage.Nack m -> m.correlationId();
        default -> null;
      };
    }

    void stop() {
      readerThread.interrupt();
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

  /**
   * Same classpath-scanning trick {@code RealBundledHookAndProbeInvocationTest} uses: the compiled
   * fixture's {@code requires static com.gimle.module} needs {@code gimle-module}/{@code
   * gimle-core} (and their own runtime deps) on the compiler's module path, whether this test runs
   * against installed jars or a reactor build's exploded {@code target/classes}.
   */
  private static List<Path> findPlatformJars() {
    String[] jarNeedles = {"slf4j-api-", "logback-classic-", "logback-core-", "snakeyaml-"};
    String[] moduleArtifacts = {"gimle-module", "gimle-core"};
    List<Path> result = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    for (String entry : cp.split(File.pathSeparator)) {
      String fileName = Path.of(entry).getFileName().toString();
      String normalized = entry.replace('\\', '/');
      for (String needle : jarNeedles) {
        if (fileName.startsWith(needle)
            && fileName.endsWith(".jar")
            && !fileName.contains("tests")) {
          result.add(Path.of(entry));
        }
      }
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
}
