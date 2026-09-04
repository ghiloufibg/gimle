package com.gimle.ivaldi.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortPreflightTest {

  private static Topology parse(String yaml) {
    return TopologyParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private static String topologyOnPort(int controlPlanePort) {
    return """
        name: t
        machines:
          - {name: local, host: 127.0.0.1}
        runtime:
          dataRoot: /tmp/gimle-ivaldi-preflight-test
        store:
          replicas:
            - {machine: local, raftPort: 19080, clientPort: 19091}
        controlPlane:
          replicas:
            - {machine: local, port: %d}
        fafnir:
          keyFile: /tmp/fafnir.key
          replicas:
            - {machine: local, port: 19092}
        """
        .formatted(controlPlanePort);
  }

  @Test
  void reports_no_conflicts_when_every_declared_port_is_free() {
    Topology topology = parse(topologyOnPort(19999));

    List<String> conflicts = PortPreflight.conflictsOn(topology, "local");

    assertTrue(conflicts.isEmpty(), "expected no conflicts, got: " + conflicts);
  }

  @Test
  void reports_a_conflict_for_a_port_something_is_already_listening_on() throws IOException {
    try (ServerSocket bound = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      Topology topology = parse(topologyOnPort(bound.getLocalPort()));

      List<String> conflicts = PortPreflight.conflictsOn(topology, "local");

      assertEquals(1, conflicts.size());
      assertTrue(conflicts.get(0).contains("controlPlane"));
      assertTrue(conflicts.get(0).contains(String.valueOf(bound.getLocalPort())));
    }
  }

  @Test
  void ignores_ports_declared_on_a_different_machine() {
    Topology topology =
        parse(
            """
            name: t
            machines:
              - {name: local, host: 127.0.0.1}
              - {name: other, host: 127.0.0.1}
            runtime:
              dataRoot: /tmp/gimle-ivaldi-preflight-test
            store:
              replicas:
                - {machine: other, raftPort: 19080, clientPort: 19091}
            controlPlane:
              replicas:
                - {machine: local, port: 19999}
            fafnir:
              keyFile: /tmp/fafnir.key
              replicas:
                - {machine: local, port: 19092}
            """);

    List<String> conflicts = PortPreflight.conflictsOn(topology, "local");

    assertTrue(conflicts.isEmpty(), "expected no conflicts, got: " + conflicts);
  }
}
