package com.gimle.hilmir.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StoreEndpoints#resolve} in isolation, no live store needed: the {@code --topology}
 * derivation against {@code topology.store().replicas()}/{@code topology.machines()}, the {@code
 * --server} parse, and the mutual-exclusivity flag validation.
 */
class StoreEndpointsTest {

  @TempDir Path tempDir;

  private Path writeTopology(final String yaml) throws IOException {
    final Path file = tempDir.resolve("topology.yaml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void topology_resolves_store_replica_endpoints_against_machine_hosts() throws IOException {
    final Path file =
        writeTopology(
            """
            name: two-store
            machines:
              - {name: m1, host: 10.0.0.1}
              - {name: m2, host: 10.0.0.2}
            store:
              replicas:
                - {machine: m1, raftPort: 7100, clientPort: 7200}
                - {machine: m2, raftPort: 7100, clientPort: 7200}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            """);

    final List<SocketAddress> endpoints =
        StoreEndpoints.resolve(List.of("--topology", file.toString()));

    assertEquals(
        List.of(new InetSocketAddress("10.0.0.1", 7200), new InetSocketAddress("10.0.0.2", 7200)),
        endpoints);
  }

  @Test
  void topology_with_no_store_replicas_is_a_clean_error() throws IOException {
    final Path file =
        writeTopology(
            """
            name: no-store
            machines:
              - {name: m1, host: 10.0.0.1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            """);

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> StoreEndpoints.resolve(List.of("--topology", file.toString())));
    assertEquals("topology declares no store replicas", e.getMessage());
  }

  @Test
  void server_flag_parses_a_single_endpoint() {
    final List<SocketAddress> endpoints =
        StoreEndpoints.resolve(List.of("--server", "127.0.0.1:7200"));
    assertEquals(List.of(new InetSocketAddress("127.0.0.1", 7200)), endpoints);
  }

  @Test
  void server_flag_parses_a_comma_separated_endpoint_list() {
    final List<SocketAddress> endpoints =
        StoreEndpoints.resolve(List.of("--server", "10.0.0.1:7200,10.0.0.2:7201"));
    assertEquals(
        List.of(new InetSocketAddress("10.0.0.1", 7200), new InetSocketAddress("10.0.0.2", 7201)),
        endpoints);
  }

  @Test
  void neither_topology_nor_server_is_a_clean_usage_error() {
    final HilmirException e =
        assertThrows(HilmirException.class, () -> StoreEndpoints.resolve(List.of()));
    assertEquals(
        "exactly one of --topology <file> or --server <host:clientPort>[,<host:clientPort>...] "
            + "is required",
        e.getMessage());
  }

  @Test
  void both_topology_and_server_is_a_clean_usage_error() throws IOException {
    final Path file =
        writeTopology(
            """
            name: irrelevant
            machines:
              - {name: m1, host: 10.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            """);

    assertThrows(
        HilmirException.class,
        () ->
            StoreEndpoints.resolve(
                List.of("--topology", file.toString(), "--server", "127.0.0.1:7200")));
  }
}
