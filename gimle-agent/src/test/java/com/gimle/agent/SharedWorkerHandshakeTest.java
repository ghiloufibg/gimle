package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A worker JVM's {@code Hello} describes the worker, not the one instance whose read loop happened
 * to receive it. Under Tier 1 density several instances share one worker JVM and one control
 * connection while exactly one handshake ever arrives on it, so these drive {@link
 * AgentMain#applyWorkerHandshake} directly and read the result back through the same {@code
 * observationJson} the control plane sees over the heartbeat.
 */
class SharedWorkerHandshakeTest {

  private static final ControlMessage.Hello HELLO =
      new ControlMessage.Hello("worker-7", 4242L, "/tmp/gimle/f.sock", "10.0.0.5", 9001);

  @Test
  void an_instance_packed_onto_a_shared_worker_reports_that_workers_id() throws IOException {
    WorkerConnection shared = unconnectedConnection();
    SupervisedInstance owner = instance("orders", 0);
    SupervisedInstance packed = instance("billing", 0);
    owner.connection = shared;
    // The interesting case: packed onto a worker that had already connected but had not yet
    // handshaked, so there was nothing for installIntoExistingWorker to copy across.
    packed.connection = shared;
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders#0", owner);
    supervised.put("billing#0", packed);

    AgentMain.applyWorkerHandshake(owner, supervised, shared, HELLO);

    assertEquals("worker-7", AgentMain.observationJson(owner).get("workerId"));
    assertEquals(
        "worker-7",
        AgentMain.observationJson(packed).get("workerId"),
        "a density-packed instance must report the worker JVM it actually runs in");
    assertEquals("/tmp/gimle/f.sock", packed.fabricUdsPath);
    assertEquals(9001, packed.fabricTcpAddress.getPort());
  }

  @Test
  void an_instance_on_a_different_worker_is_left_alone() throws IOException {
    WorkerConnection shared = unconnectedConnection();
    WorkerConnection other = unconnectedConnection();
    SupervisedInstance owner = instance("orders", 0);
    SupervisedInstance elsewhere = instance("reports", 0);
    owner.connection = shared;
    elsewhere.connection = other;
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders#0", owner);
    supervised.put("reports#0", elsewhere);

    AgentMain.applyWorkerHandshake(owner, supervised, shared, HELLO);

    assertFalse(
        AgentMain.observationJson(elsewhere).containsKey("workerId"),
        "an instance on another worker has no handshake of this one's to report");
  }

  private static SupervisedInstance instance(String deploymentName, int index) {
    AssignedInstance assigned =
        new AssignedInstance(
            deploymentName,
            index,
            new ModuleId(deploymentName, Version.parse("1.0.0")),
            "/does/not/matter.jar",
            Optional.empty());
    return new SupervisedInstance(assigned, null, null, null);
  }

  /**
   * Only the connection's identity matters here -- the fan-out selects instances by reference
   * equality against the connection the handshake arrived on -- so an unconnected channel is
   * enough, and avoids standing up a worker subprocess to obtain one.
   */
  private static WorkerConnection unconnectedConnection() throws IOException {
    return new WorkerConnection(SocketChannel.open());
  }
}
