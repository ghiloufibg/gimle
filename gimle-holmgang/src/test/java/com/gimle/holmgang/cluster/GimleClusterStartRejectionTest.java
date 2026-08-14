package com.gimle.holmgang.cluster;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.holmgang.topology.ClusterTopology;
import com.gimle.holmgang.topology.Transport;
import org.junit.jupiter.api.Test;

/**
 * The one unsupported topology combination is rejected at model construction, before a single
 * process could ever spawn -- never silently downgraded, matching the platform's own Tier 3
 * precedent.
 */
class GimleClusterStartRejectionTest {

  @Test
  void a_fault_proxied_mtls_topology_is_rejected_at_model_construction() {
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> ClusterTopology.named("bad").transport(Transport.MTLS).faultsProxied().build());
    assertTrue(e.getMessage().contains("not supported"));
  }
}
