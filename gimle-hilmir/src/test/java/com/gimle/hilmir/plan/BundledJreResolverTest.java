package com.gimle.hilmir.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.StoreRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledJreResolverTest {

  @TempDir Path tempDir;

  private static final ResolvedRuntime RUNTIME =
      new ResolvedRuntime("real-java", "cp", Path.of("/data"));

  private static Topology topologyWith(final boolean useBundledJre) {
    return new Topology(
        "t",
        Transport.PLAINTEXT,
        Optional.empty(),
        List.of(),
        new RuntimeSettings(
            Optional.empty(), Optional.empty(), Optional.empty(), useBundledJre, Optional.empty()),
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of()),
        new FafnirRole(Optional.empty(), List.of()),
        new MuninnRole(List.of()),
        new AndvariRole(List.of()),
        List.of(),
        Map.of());
  }

  @Test
  void returns_java_executable_unchanged_when_use_bundled_jre_is_false_even_with_no_gimle_home() {
    final Topology topology = topologyWith(false);
    assertEquals(
        "real-java", BundledJreResolver.resolveJavaExecutable(topology, RUNTIME, "mimir", null));
  }

  @Test
  void returns_the_bundled_java_path_when_use_bundled_jre_is_true_and_the_binary_exists()
      throws IOException {
    final Path javaBin = tempDir.resolve("jre/mimir/bin/java");
    Files.createDirectories(javaBin.getParent());
    Files.createFile(javaBin);

    final Topology topology = topologyWith(true);
    final String resolved =
        BundledJreResolver.resolveJavaExecutable(topology, RUNTIME, "mimir", tempDir.toString());

    assertEquals(javaBin.toString(), resolved);
  }

  @Test
  void fails_clearly_when_use_bundled_jre_is_true_and_gimle_home_is_unset() {
    final Topology topology = topologyWith(true);
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> BundledJreResolver.resolveJavaExecutable(topology, RUNTIME, "mimir", null));
    assertTrue(e.getMessage().contains("GIMLE_HOME"));
  }

  @Test
  void fails_clearly_when_use_bundled_jre_is_true_and_gimle_home_is_blank() {
    final Topology topology = topologyWith(true);
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> BundledJreResolver.resolveJavaExecutable(topology, RUNTIME, "mimir", "  "));
    assertTrue(e.getMessage().contains("GIMLE_HOME"));
  }

  @Test
  void fails_clearly_naming_the_missing_path_when_the_components_bundled_java_is_absent() {
    final Topology topology = topologyWith(true);
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                BundledJreResolver.resolveJavaExecutable(
                    topology, RUNTIME, "fafnir", tempDir.toString()));
    final Path expectedMissingPath = tempDir.resolve("jre/fafnir/bin/java");
    assertTrue(e.getMessage().contains(expectedMissingPath.toString()));
    assertTrue(e.getMessage().contains("fafnir"));
  }

  @Test
  void the_public_overload_never_reads_gimle_home_when_use_bundled_jre_is_false() {
    // No GIMLE_HOME is set in this test process's own environment (or if it happens to be, this
    // call must not depend on it) -- the point is the false branch returns before any env lookup.
    final Topology topology = topologyWith(false);
    assertEquals("real-java", BundledJreResolver.resolveJavaExecutable(topology, RUNTIME, "cli"));
  }
}
