package com.gimle.ragnarok.target.adminapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.ragnarok.RagnarokException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parsing and validation of the {@code adminApi:} block. */
final class AdminApiSpecParserTest {

  private static AdminApiSpec parse(final String yaml) {
    final Map<?, ?> root =
        (Map<?, ?>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    return AdminApiSpecParser.parse(root);
  }

  @Test
  void parses_a_node_id_to_endpoint_map() {
    final AdminApiSpec spec =
        parse(
            """
            nodes:
              - {nodeId: node-1, endpoint: 'https://10.0.0.5:9500'}
              - {nodeId: node-2, endpoint: 'https://10.0.0.6:9500'}
            """);
    assertEquals(2, spec.endpointByNodeId().size());
    assertEquals("https://10.0.0.5:9500", spec.endpointByNodeId().get("node-1"));
  }

  @Test
  void no_nodes_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("nodes: []\n"));
  }

  @Test
  void a_node_with_no_endpoint_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("nodes:\n  - {nodeId: node-1}\n"));
  }
}
