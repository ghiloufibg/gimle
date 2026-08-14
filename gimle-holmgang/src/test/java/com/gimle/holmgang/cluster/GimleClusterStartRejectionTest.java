package com.gimle.holmgang.cluster;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopology;
import com.gimle.holmgang.topology.Transport;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The unimplemented topology capabilities are rejected outright before a single process spawns --
 * "not built yet", never a silent downgrade to plaintext or unproxied, matching the platform's own
 * Tier 3 precedent.
 */
class GimleClusterStartRejectionTest {

  @TempDir Path tempDir;

  @Test
  void an_mtls_topology_is_rejected_rather_than_silently_downgraded() {
    final ClusterSpec spec = ClusterTopology.named("tls").transport(Transport.MTLS).build();
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> GimleCluster.start(spec, tempDir));
    assertTrue(e.getMessage().contains("not implemented yet"));
  }

  @Test
  void a_fault_proxied_topology_is_rejected_rather_than_silently_unproxied() {
    final ClusterSpec spec = ClusterTopology.named("proxied").faultsProxied().build();
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> GimleCluster.start(spec, tempDir));
    assertTrue(e.getMessage().contains("not implemented yet"));
  }
}
