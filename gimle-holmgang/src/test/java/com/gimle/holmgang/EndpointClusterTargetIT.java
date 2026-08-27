package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.surtr.ExampleModuleJarSource;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import com.gimle.ragnarok.fenrir.ChaosLedger;
import com.gimle.ragnarok.fenrir.Fenrir;
import com.gimle.ragnarok.fenrir.FenrirPlan;
import com.gimle.ragnarok.fenrir.Pools;
import com.gimle.ragnarok.surtr.SurtrRunResult;
import com.gimle.ragnarok.surtr.SurtrRunner;
import com.gimle.ragnarok.surtr.SurtrWorkload;
import com.gimle.ragnarok.surtr.SurtrWorkloadParser;
import com.gimle.ragnarok.target.endpoint.EndpointClusterTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the design's central claim about {@code EndpointClusterTarget}: pointed at a real cluster
 * over HTTP and a real store client port only -- no process handles, no {@code GimleCluster} in
 * sight from the target's own perspective -- Surtr runs a real workload to completion, the
 * store-health gates answer for real off a genuine {@link com.gimle.mimir.rpc.StoreClient} RPC, and
 * every Fenrir fault this target has no way to fire (it holds no process handle for any of them) is
 * recorded {@code SKIPPED} with the expected reason rather than thrown. The cluster itself is
 * booted through the harness only to have something real to point the endpoint target at -- exactly
 * the honest degradation the design documents, not the harness's own {@code asClusterTarget()}
 * adapter.
 */
@Tag("holmgang")
class EndpointClusterTargetIT {

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void surtr_and_fenrir_both_run_against_an_endpoint_only_view_of_a_real_cluster() {
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath("topologies/surtr-density.yaml");
    final Path workDir =
        Path.of("target", "holmgang", "endpoint-target-" + Long.toHexString(System.nanoTime()));
    final GimleCluster cluster = GimleCluster.start(spec, workDir);
    try (EndpointClusterTarget target =
        new EndpointClusterTarget(
            List.of(cluster.controlPlaneBaseUrl(0)),
            cluster.operatorHttpClient(),
            cluster.storeClientEndpoints(),
            List.of(),
            List.of(),
            workDir.resolve("endpoint-target"))) {

      // The store-health gates: real RPC against the real store, no process control involved.
      assertTrue(target.storeLeaderId().isPresent(), "expected a real store leader to be known");
      assertEquals(1, target.storeMemberIds().size());

      // Surtr, over HTTP only.
      final SurtrWorkload workload = SurtrWorkloadParser.resolve("module-density");
      final SurtrRunResult result =
          new SurtrRunner(target, workload, new ExampleModuleJarSource()).run();
      assertTrue(result.passed(), "Surtr gates failed against the endpoint target: " + result);

      // Fenrir: every fault this target could fire needs process control it doesn't have.
      final FenrirPlan plan =
          FenrirPlan.seeded(1)
              .soakFor(Duration.ofSeconds(5))
              .strikeEvery(Duration.ofSeconds(1))
              .pool(Pools.controlPlaneBounces())
              .pool(Pools.storeBounces())
              .build();
      final ChaosLedger ledger = Fenrir.unleash(target, plan);
      assertTrue(
          ledger.executedCount() == 0, "no fault should have actually fired\n" + ledger.render());
      assertTrue(
          ledger.strikeCount() > 0, "the soak should have scheduled at least one strike attempt");
      assertTrue(ledger.allRecovered(), "vacuously true with nothing executed\n" + ledger.render());
    } finally {
      cluster.close();
    }
  }
}
