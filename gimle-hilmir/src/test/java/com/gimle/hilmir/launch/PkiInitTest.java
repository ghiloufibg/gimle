package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.StoreRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PkiInit}'s own pre-spawn validation only -- actually running {@code
 * PkiBootstrapMain} needs {@code gimle-pki} on the resolved classpath, which this module
 * deliberately never depends on (see the class's own javadoc: it is spawned, not linked).
 */
class PkiInitTest {

  @TempDir Path tempDir;

  private static Topology plaintextTopology() {
    return new Topology(
        "no-tls",
        Transport.PLAINTEXT,
        Optional.empty(),
        List.of(),
        RuntimeSettings.EMPTY,
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of()),
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
}
