package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PkiInit}'s own pre-spawn validation and command-building only -- actually running
 * {@code PkiBootstrapMain} needs {@code gimle-pki} on the resolved classpath, which this module
 * deliberately never depends on (see the class's own javadoc: it is spawned, not linked).
 */
class PkiInitTest {

  @TempDir Path tempDir;

  private static Topology plaintextTopology() {
    return topologyWith(Transport.PLAINTEXT, Optional.empty(), RuntimeSettings.EMPTY);
  }

  private static Topology mtlsTopology(final Path materialDir, final boolean useBundledJre) {
    return topologyWith(
        Transport.MTLS,
        Optional.of(new TlsMaterial(materialDir)),
        new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty(), useBundledJre));
  }

  private static Topology topologyWith(
      final Transport transport,
      final Optional<TlsMaterial> tls,
      final RuntimeSettings runtimeSettings) {
    return new Topology(
        "cluster",
        transport,
        tls,
        List.of(new Machine("m1", "host1.example.com")),
        runtimeSettings,
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of(new ServiceReplica("m1", 8080))),
        new FafnirRole(Optional.empty(), List.of()),
        new MuninnRole(List.of()),
        new AndvariRole(List.of()),
        List.of(),
        Map.of());
  }

  @Test
  void refuses_to_run_against_a_topology_with_no_tls_material_dir() {
    final ResolvedRuntime runtime = new ResolvedRuntime("java", "cp", tempDir);
    final PrintStream out =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    final HilmirException e =
        assertThrows(HilmirException.class, () -> PkiInit.run(plaintextTopology(), runtime, out));

    assertTrue(e.getMessage().contains("tls.materialDir"));
  }

  @Test
  void build_command_uses_runtime_java_executable_when_use_bundled_jre_is_false() {
    final Topology topology = mtlsTopology(tempDir.resolve("tls"), false);
    final ResolvedRuntime runtime = new ResolvedRuntime("real-java", "cp", tempDir);

    final List<String> command =
        PkiInit.buildCommand(
            topology, runtime, null, tempDir.resolve("tls"), "cluster-ca", "host1.example.com");

    assertEquals("real-java", command.get(0));
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
            "host1.example.com");

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
                    "host1.example.com"));

    assertTrue(e.getMessage().contains("GIMLE_HOME"));
  }
}
