package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression coverage for M19: a hosted module's declared readiness probe genuinely running inside
 * its worker (see {@code WorkerRuntimeTest}) used to have no way to reach this agent at all -- the
 * wire message it would travel as, {@link ControlMessage.HealthReport}, was fully codec-supported
 * but never sent by any production code and never handled by {@link AgentMain#readLoop}. Before
 * this fix, {@link AgentMain#observationJson} reported {@code ready} purely from {@code
 * lifecycleState == ACTIVE}, so a readiness probe could fail forever without ever surfacing.
 */
class AgentHealthReportTest {

  private static AssignedInstance assignedInstance() {
    return new AssignedInstance(
        "web-ui",
        0,
        new ModuleId("web-ui", Version.parse("1.0.0")),
        "/does/not/matter.jar",
        Optional.empty());
  }

  @Test
  @Timeout(15)
  void a_health_report_of_not_ready_overrides_the_active_derived_default() throws Exception {
    Path socketPath = Files.createTempDirectory("gimle-agent-health-uds-").resolve("c.sock");
    ControlChannelServer server = new ControlChannelServer(socketPath);
    SocketChannel workerSocket = SocketChannel.open(StandardProtocolFamily.UNIX);
    workerSocket.connect(UnixDomainSocketAddress.of(socketPath));

    SupervisedInstance instance = new SupervisedInstance(assignedInstance(), null, server, null);
    instance.connection = server.accept();
    instance.lifecycleState = "ACTIVE";
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("web-ui#0", instance);

    Thread readerThread =
        Thread.ofVirtual()
            .start(
                () ->
                    AgentMain.readLoop(
                        instance,
                        "web-ui#0",
                        null,
                        new ServiceCatalog(),
                        HttpClient.newHttpClient(),
                        URI.create("http://127.0.0.1:1"),
                        "node-a",
                        supervised,
                        null,
                        new ConcurrentHashMap<>()));
    try {
      // Before any HealthReport arrives, ACTIVE alone is treated as ready -- the long-standing
      // fallback, unaffected by this fix.
      assertTrue((Boolean) AgentMain.observationJson(instance).get("ready"));

      sendRaw(
          workerSocket,
          new ControlMessage.HealthReport(
              AgentMain.moduleInstanceIdOf(assignedInstance()), true, false));
      Await.until(
          () -> !(Boolean) AgentMain.observationJson(instance).get("ready"), Duration.ofSeconds(5));

      Map<String, Object> observation = AgentMain.observationJson(instance);
      assertFalse((Boolean) observation.get("ready"));
      assertTrue((Boolean) observation.get("alive"), "HealthReport must never override alive");

      sendRaw(
          workerSocket,
          new ControlMessage.HealthReport(
              AgentMain.moduleInstanceIdOf(assignedInstance()), true, true));
      Await.until(
          () -> (Boolean) AgentMain.observationJson(instance).get("ready"), Duration.ofSeconds(5));
    } finally {
      workerSocket.close();
      readerThread.interrupt();
    }
  }

  @Test
  @Timeout(15)
  void a_module_state_change_clears_a_stale_readiness_reading_from_before_it() throws Exception {
    Path socketPath = Files.createTempDirectory("gimle-agent-health-uds-").resolve("c.sock");
    ControlChannelServer server = new ControlChannelServer(socketPath);
    SocketChannel workerSocket = SocketChannel.open(StandardProtocolFamily.UNIX);
    workerSocket.connect(UnixDomainSocketAddress.of(socketPath));

    SupervisedInstance instance = new SupervisedInstance(assignedInstance(), null, server, null);
    instance.connection = server.accept();
    instance.lifecycleState = "ACTIVE";
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    supervised.put("web-ui#0", instance);

    Thread readerThread =
        Thread.ofVirtual()
            .start(
                () ->
                    AgentMain.readLoop(
                        instance,
                        "web-ui#0",
                        null,
                        new ServiceCatalog(),
                        HttpClient.newHttpClient(),
                        URI.create("http://127.0.0.1:1"),
                        "node-a",
                        supervised,
                        null,
                        new ConcurrentHashMap<>()));
    try {
      sendRaw(
          workerSocket,
          new ControlMessage.HealthReport(
              AgentMain.moduleInstanceIdOf(assignedInstance()), true, false));
      Await.until(
          () -> !(Boolean) AgentMain.observationJson(instance).get("ready"), Duration.ofSeconds(5));

      // A restart cycle takes the module through STOPPING/UNINSTALLED/INSTALLED/.../ACTIVE again
      // -- the stale "not ready" reading from the previous ACTIVE window must not survive it.
      sendRaw(
          workerSocket,
          new ControlMessage.ModuleStateChanged(
              AgentMain.moduleInstanceIdOf(assignedInstance()), "STOPPING"));
      Await.until(() -> "STOPPING".equals(instance.lifecycleState), Duration.ofSeconds(5));

      assertEquals(Optional.empty(), instance.readinessReported);

      sendRaw(
          workerSocket,
          new ControlMessage.ModuleStateChanged(
              AgentMain.moduleInstanceIdOf(assignedInstance()), "ACTIVE"));
      Await.until(() -> "ACTIVE".equals(instance.lifecycleState), Duration.ofSeconds(5));

      assertTrue(
          (Boolean) AgentMain.observationJson(instance).get("ready"),
          "back to ACTIVE with no HealthReport yet this cycle must fall back to the ACTIVE-derived"
              + " default, not the previous cycle's stale reading");
    } finally {
      workerSocket.close();
      readerThread.interrupt();
    }
  }

  private static void sendRaw(SocketChannel socket, ControlMessage message) throws IOException {
    String line = ControlMessageCodec.encode(message) + "\n";
    socket.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
  }
}
