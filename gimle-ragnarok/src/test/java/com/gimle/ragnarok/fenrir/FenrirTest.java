package com.gimle.ragnarok.fenrir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.fenrir.ChaosLedger.Entry;
import com.gimle.ragnarok.fenrir.ChaosLedger.Outcome;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.ControlPlaneClient;
import com.gimle.ragnarok.target.GimleProcess;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.WorkerHandle;
import com.gimle.testkit.heimdall.HeimdallScope;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link Fenrir}'s store-bounce quorum guard, contrasting a target with no process-control
 * visibility into its stores at all -- the {@code EndpointClusterTarget} shape -- against one that
 * genuinely sees every store dead. Both must never be confused with each other: the first has
 * nothing to say about store health, the second is a real quorum floor.
 */
final class FenrirTest {

  @Test
  @Timeout(10)
  void a_target_with_no_store_process_visibility_never_reports_a_fabricated_live_count() {
    FakeClusterTarget cluster =
        new FakeClusterTarget(3, List.of("store-0", "store-1", "store-2"), Optional.of("store-0"));
    // No store process handle configured at any index -- exactly what an HTTP-only target (or an
    // SSH target with no agents: block) reports regardless of the store cluster's real health.

    ChaosLedger ledger = runSingleStoreBouncePlan(cluster);

    assertFalse(ledger.entries().isEmpty());
    for (Entry entry : ledger.entries()) {
      assertEquals(Outcome.SKIPPED, entry.outcome());
      assertFalse(
          entry.skipReason().startsWith("quorum floor:"),
          "a target with no process visibility must never report a fabricated live count: "
              + entry.skipReason());
      assertTrue(
          entry.skipReason().contains("no process-control visibility"),
          "expected an honest no-visibility reason, got: " + entry.skipReason());
    }
  }

  @Test
  @Timeout(10)
  void a_target_that_genuinely_sees_every_store_dead_still_reports_the_real_quorum_floor() {
    FakeClusterTarget cluster =
        new FakeClusterTarget(3, List.of("store-0", "store-1", "store-2"), Optional.of("store-0"));
    cluster.storeProcesses(
        new FakeGimleProcess("store-0"),
        new FakeGimleProcess("store-1"),
        new FakeGimleProcess("store-2"));
    // Every process handle is present (real visibility) but dead -- a genuine outage, not a
    // capability gap, so the original quorum-floor message must still fire unchanged.

    ChaosLedger ledger = runSingleStoreBouncePlan(cluster);

    assertFalse(ledger.entries().isEmpty());
    for (Entry entry : ledger.entries()) {
      assertEquals(Outcome.SKIPPED, entry.outcome());
      assertEquals("quorum floor: 0 of 3 members live", entry.skipReason());
    }
  }

  private static ChaosLedger runSingleStoreBouncePlan(final ClusterTarget cluster) {
    FenrirPlan plan =
        FenrirPlan.seeded(1)
            .soakFor(Duration.ofMillis(60))
            .strikeEvery(Duration.ofMillis(10))
            .gateTimeout(Duration.ofSeconds(1))
            .pool(new Pool(FaultKind.STORE_BOUNCE, 1, Duration.ofMillis(1)))
            .build();
    return Fenrir.unleash(cluster, plan);
  }

  /**
   * A {@link ClusterTarget} exercising only what a store-bounce strike touches before it either
   * skips or would start bouncing a real process -- every other accessor reports the same "nothing
   * here" shape {@code EndpointClusterTarget} reports for a capability it genuinely lacks.
   */
  private static final class FakeClusterTarget implements ClusterTarget {

    private final int storeCount;
    private final List<String> storeMemberIds;
    private final Optional<String> storeLeaderId;
    private List<GimleProcess> storeProcesses = List.of();

    FakeClusterTarget(
        final int storeCount,
        final List<String> storeMemberIds,
        final Optional<String> storeLeaderId) {
      this.storeCount = storeCount;
      this.storeMemberIds = storeMemberIds;
      this.storeLeaderId = storeLeaderId;
    }

    void storeProcesses(final GimleProcess... processes) {
      this.storeProcesses = List.of(processes);
    }

    @Override
    public List<String> controlPlaneBaseUrls() {
      return List.of();
    }

    @Override
    public int controlPlaneCount() {
      return 0;
    }

    @Override
    public ControlPlaneClient api() {
      throw new UnsupportedOperationException("not needed once quorumGuard skips the strike");
    }

    @Override
    public ControlPlaneClient api(final int controlPlaneIndex) {
      throw new UnsupportedOperationException("not needed once quorumGuard skips the strike");
    }

    @Override
    public HeimdallScope when() {
      throw new UnsupportedOperationException("not needed once quorumGuard skips the strike");
    }

    @Override
    public HeimdallScope when(final int controlPlaneIndex) {
      throw new UnsupportedOperationException("not needed once quorumGuard skips the strike");
    }

    @Override
    public Optional<String> storeLeaderId() {
      return storeLeaderId;
    }

    @Override
    public List<String> storeMemberIds() {
      return storeMemberIds;
    }

    @Override
    public int storeCount() {
      return storeCount;
    }

    @Override
    public Optional<GimleProcess> store(final int index) {
      return index < storeProcesses.size()
          ? Optional.of(storeProcesses.get(index))
          : Optional.empty();
    }

    @Override
    public Optional<GimleProcess> storeLeader() {
      return Optional.empty();
    }

    @Override
    public Optional<GimleProcess> controlPlane(final int index) {
      return Optional.empty();
    }

    @Override
    public int fafnirCount() {
      return 0;
    }

    @Override
    public Optional<GimleProcess> fafnir(final int index) {
      return Optional.empty();
    }

    @Override
    public int muninnCount() {
      return 0;
    }

    @Override
    public Optional<GimleProcess> muninn(final int index) {
      return Optional.empty();
    }

    @Override
    public boolean muninnServing(final int index) {
      return false;
    }

    @Override
    public int andvariCount() {
      return 0;
    }

    @Override
    public Optional<GimleProcess> andvari(final int index) {
      return Optional.empty();
    }

    @Override
    public boolean andvariServing(final int index) {
      return false;
    }

    @Override
    public Optional<WorkerHandle> workerFor(final String deploymentName, final int instanceIndex) {
      return Optional.empty();
    }

    @Override
    public Optional<NetworkFaultInjector> faults() {
      return Optional.empty();
    }

    @Override
    public void close() {}
  }

  /**
   * A store process handle that never restarts -- this test never runs the bounce far enough to.
   */
  private static final class FakeGimleProcess implements GimleProcess {

    private final String id;

    FakeGimleProcess(final String id) {
      this.id = id;
    }

    @Override
    public String role() {
      return "STORE";
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public long pid() {
      return -1;
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public void kill() {}

    @Override
    public void killWithDescendants() {}

    @Override
    public void restart() {}

    @Override
    public void onExit(final Runnable callback) {}

    @Override
    public boolean exitWasExpected() {
      return false;
    }

    @Override
    public Path logFile() {
      return Path.of("/dev/null");
    }

    @Override
    public String endpoint() {
      return "127.0.0.1:0";
    }
  }
}
