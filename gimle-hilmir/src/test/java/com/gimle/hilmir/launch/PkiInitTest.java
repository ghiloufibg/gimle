package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.StoreRole;
import com.gimle.hilmir.topology.TlsMaterial;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PkiInit}'s own pre-spawn validation, command-building, and Fafnir key generation --
 * actually running {@code PkiBootstrapMain} needs {@code gimle-pki} on the resolved classpath,
 * which this module deliberately never depends on (see the class's own javadoc: it is spawned, not
 * linked).
 */
class PkiInitTest {

  @TempDir Path tempDir;

  private static Topology plaintextTopology() {
    return topologyWith(
        Transport.PLAINTEXT,
        Optional.empty(),
        RuntimeSettings.EMPTY,
        List.of(new Machine("m1", "host1.example.com", Optional.empty(), Optional.empty())),
        new FafnirRole(Optional.empty(), List.of()));
  }

  private static Topology mtlsTopology(final Path materialDir, final boolean useBundledJre) {
    return topologyWith(
        Transport.MTLS,
        Optional.of(new TlsMaterial(materialDir)),
        new RuntimeSettings(
            Optional.empty(), Optional.empty(), Optional.empty(), useBundledJre, Optional.empty()),
        List.of(new Machine("m1", "host1.example.com", Optional.empty(), Optional.empty())),
        new FafnirRole(Optional.empty(), List.of()));
  }

  private static Topology topologyWith(
      final Transport transport,
      final Optional<TlsMaterial> tls,
      final RuntimeSettings runtimeSettings,
      final List<Machine> machines,
      final FafnirRole fafnir) {
    return new Topology(
        "cluster",
        transport,
        tls,
        machines,
        runtimeSettings,
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of(new ServiceReplica("m1", 8080))),
        fafnir,
        new MuninnRole(List.of()),
        new AndvariRole(List.of()),
        List.of(),
        Map.of());
  }

  private static PrintStream discardingOut() {
    return new PrintStream(ByteArrayOutputStream.nullOutputStream());
  }

  @Test
  void refuses_to_run_against_a_plaintext_topology_with_no_multi_machine_fafnir() {
    final ResolvedRuntime runtime = new ResolvedRuntime("java", "cp", tempDir);

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> PkiInit.run(plaintextTopology(), runtime, discardingOut()));

    assertTrue(e.getMessage().contains("tls.materialDir"));
    assertTrue(e.getMessage().contains("fafnir"));
  }

  @Test
  void a_plaintext_topology_with_fafnir_spanning_multiple_machines_generates_only_the_key()
      throws IOException {
    final Path keyFile = tempDir.resolve("fafnir.key");
    final Topology topology =
        topologyWith(
            Transport.PLAINTEXT,
            Optional.empty(),
            RuntimeSettings.EMPTY,
            List.of(
                new Machine("m1", "h1", Optional.empty(), Optional.empty()),
                new Machine("m2", "h2", Optional.empty(), Optional.empty())),
            new FafnirRole(
                Optional.of(keyFile),
                List.of(new ServiceReplica("m1", 9092), new ServiceReplica("m2", 9092))));
    final ResolvedRuntime runtime = new ResolvedRuntime("java", "cp", tempDir);

    PkiInit.run(topology, runtime, discardingOut());

    assertTrue(Files.exists(keyFile));
    assertEquals(32, Files.readAllBytes(keyFile).length);
  }

  @Test
  void an_existing_fafnir_key_file_is_never_overwritten() throws IOException {
    final Path keyFile = tempDir.resolve("fafnir.key");
    final byte[] originalContent = new byte[] {1, 2, 3, 4};
    Files.write(keyFile, originalContent);
    final Topology topology =
        topologyWith(
            Transport.PLAINTEXT,
            Optional.empty(),
            RuntimeSettings.EMPTY,
            List.of(
                new Machine("m1", "h1", Optional.empty(), Optional.empty()),
                new Machine("m2", "h2", Optional.empty(), Optional.empty())),
            new FafnirRole(
                Optional.of(keyFile),
                List.of(new ServiceReplica("m1", 9092), new ServiceReplica("m2", 9092))));
    final ResolvedRuntime runtime = new ResolvedRuntime("java", "cp", tempDir);

    PkiInit.run(topology, runtime, discardingOut());

    assertTrue(Arrays.equals(originalContent, Files.readAllBytes(keyFile)));
  }

  @Test
  void a_single_machine_fafnir_generates_no_key_under_the_nothing_to_do_guard() {
    final Path keyFile = tempDir.resolve("fafnir.key");
    final Topology topology =
        topologyWith(
            Transport.PLAINTEXT,
            Optional.empty(),
            RuntimeSettings.EMPTY,
            List.of(new Machine("m1", "h1", Optional.empty(), Optional.empty())),
            new FafnirRole(Optional.of(keyFile), List.of(new ServiceReplica("m1", 9092))));
    final ResolvedRuntime runtime = new ResolvedRuntime("java", "cp", tempDir);

    assertThrows(HilmirException.class, () -> PkiInit.run(topology, runtime, discardingOut()));

    assertFalse(Files.exists(keyFile));
  }

  @Test
  void build_command_uses_runtime_java_executable_when_use_bundled_jre_is_false() {
    final Topology topology = mtlsTopology(tempDir.resolve("tls"), false);
    final ResolvedRuntime runtime = new ResolvedRuntime("real-java", "cp", tempDir);

    final List<String> command =
        PkiInit.buildCommand(
            topology,
            runtime,
            null,
            tempDir.resolve("tls"),
            "cluster-ca",
            List.of("host1.example.com"));

    assertEquals("real-java", command.get(0));
  }

  @Test
  void build_command_passes_every_distinct_machine_hostname_positionally() {
    final Topology topology = mtlsTopology(tempDir.resolve("tls"), false);
    final ResolvedRuntime runtime = new ResolvedRuntime("real-java", "cp", tempDir);

    final List<String> command =
        PkiInit.buildCommand(
            topology, runtime, null, tempDir.resolve("tls"), "cluster-ca", List.of("h1", "h2"));

    assertEquals("h1", command.get(command.size() - 2));
    assertEquals("h2", command.get(command.size() - 1));
  }

  @Test
  void build_command_resolves_the_bundled_pki_java_when_use_bundled_jre_is_true()
      throws IOException {
    final Path javaBin = tempDir.resolve("jre/pki/bin/java");
    Files.createDirectories(javaBin.getParent());
    Files.createFile(javaBin);
    final Topology topology = mtlsTopology(tempDir.resolve("tls"), true);
    final ResolvedRuntime runtime = new ResolvedRuntime("real-java", "cp", tempDir);

    final List<String> command =
        PkiInit.buildCommand(
            topology,
            runtime,
            tempDir.toString(),
            tempDir.resolve("tls"),
            "cluster-ca",
            List.of("host1.example.com"));

    assertEquals(javaBin.toString(), command.get(0));
  }

  @Test
  void build_command_fails_clearly_when_use_bundled_jre_is_true_and_gimle_home_is_unset() {
    final Topology topology = mtlsTopology(tempDir.resolve("tls"), true);
    final ResolvedRuntime runtime = new ResolvedRuntime("real-java", "cp", tempDir);

    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                PkiInit.buildCommand(
                    topology,
                    runtime,
                    null,
                    tempDir.resolve("tls"),
                    "cluster-ca",
                    List.of("host1.example.com")));

    assertTrue(e.getMessage().contains("GIMLE_HOME"));
  }
}
