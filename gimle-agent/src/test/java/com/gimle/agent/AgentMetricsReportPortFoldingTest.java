package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * End-to-end proof of the exact wire path a real hosted module's {@code ModuleContext#reportPort}
 * call travels: {@code WorkerMain}'s periodic metrics loop sends a real {@code
 * ControlMessage.MetricsReport} carrying {@code ports} over a real socket, {@link
 * AgentMain#readLoop} decodes it and folds it into the matching {@link SupervisedInstance#ports},
 * and {@link AgentMain#observationJson} exposes it in the exact shape {@code
 * ServiceEndpointResolver} (in {@code gimle-controlplane}, not reachable from this module) needs to
 * resolve a live endpoint -- this test can't call that resolver directly, so it re-asserts its
 * documented contract (a {@code ports} map with exactly one entry resolves unambiguously when the
 * Service declares no targetPort) against the real map this instance produces.
 */
class AgentMetricsReportPortFoldingTest {

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
  void a_metrics_report_carrying_a_reported_port_is_folded_into_the_instances_observation()
      throws Exception {
    Path socketPath = Files.createTempDirectory("gimle-agent-metrics-uds-").resolve("c.sock");
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
          new ControlMessage.MetricsReport(
              assignedInstance().moduleId(), 0L, 0L, 0.0, 0, 0.0, Map.of("HTTP_PORT", 8080)));

      Await.until(() -> !instance.ports.isEmpty(), Duration.ofSeconds(5));
      assertEquals(Map.of("HTTP_PORT", 8080), instance.ports);

      Map<String, Object> observation = AgentMain.observationJson(instance);
      Map<?, ?> observedPorts = (Map<?, ?>) observation.get("ports");
      assertEquals(
          1,
          observedPorts.size(),
          "a Service with no targetPort only resolves an unambiguous single port");
      assertTrue(observedPorts.containsKey("HTTP_PORT"));
      assertEquals(8080, observedPorts.get("HTTP_PORT"));
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
