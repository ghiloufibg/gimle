package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.cli.spi.ClusterReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repointing a read-only view at another control plane, which is what lets a long-running extension
 * follow an operator between clusters without being restarted.
 */
class ClusterReaderContextTest {

  @TempDir Path configDir;

  @AfterEach
  void clearConfigOverride() {
    System.clearProperty(CliConfig.CONFIG_FILE_PROPERTY);
  }

  @Test
  void a_stored_context_is_resolved_to_the_endpoint_it_names() {
    writeConfig(
        """
        contexts:
          - name: staging
            server: 10.0.0.9:8080
        """);

    ClusterReader moved = reader().forContext("staging");

    assertEquals("10.0.0.9:8080", moved.serverAddress());
  }

  @Test
  void a_bare_address_is_dialled_as_given_when_no_context_carries_that_name() {
    writeConfig("contexts: []\n");

    assertEquals("127.0.0.1:9999", reader().forContext("127.0.0.1:9999").serverAddress());
    assertEquals("[::1]:8080", reader().forContext("[::1]:8080").serverAddress());
  }

  @Test
  void a_context_wins_over_an_address_that_happens_to_look_like_its_name() {
    // A context deliberately named after a host must not be shadowed by the host itself.
    writeConfig(
        """
        contexts:
          - name: control.example:8080
            server: 10.0.0.9:8080
        """);

    assertEquals("10.0.0.9:8080", reader().forContext("control.example:8080").serverAddress());
  }

  @Test
  void a_name_that_is_neither_a_context_nor_an_address_is_refused_rather_than_dialled() {
    // A typo dialled as a hostname fails later, further away, and far less clearly.
    writeConfig("contexts: []\n");

    CliException thrown = assertThrows(CliException.class, () -> reader().forContext("stging"));

    assertEquals(
        "no context named 'stging', and it is not a host:port address either", thrown.getMessage());
  }

  private ClusterReader reader() {
    return new ControlPlaneClusterReader(
        new ControlPlaneClient("127.0.0.1:8080"), "127.0.0.1:8080");
  }

  private void writeConfig(final String yaml) {
    Path path = configDir.resolve("config");
    try {
      Files.writeString(path, yaml, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("could not write the test config", e);
    }
    System.setProperty(CliConfig.CONFIG_FILE_PROPERTY, path.toString());
  }
}
