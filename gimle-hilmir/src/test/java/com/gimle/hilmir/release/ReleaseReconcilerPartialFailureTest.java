package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the ledger-goes-blank bug: a bundle whose workloads apply one PUT at a
 * time through {@link BundleApplier#applyWorkloads} and fail partway through must still leave a
 * ledger row behind for whatever did apply -- Helm's own partial-install/-upgrade behavior (see
 * {@link ReleaseReconciler}'s own javadoc reference to it), never a total absence of any record for
 * a release that has real, running instances consuming real tenant quota.
 */
class ReleaseReconcilerPartialFailureTest {

  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static RenderedBundle bundleOf(
      String name, String version, RenderedWorkload... workloads) {
    return new RenderedBundle(name, version, List.of(), List.of(), List.of(), List.of(workloads));
  }

  @Test
  void deploy_fresh_records_a_failed_revision_with_only_the_workloads_that_actually_applied()
      throws Exception {
    fake = new FakeControlPlane();
    fake.failWorkloadPut("Deployment", "third-app");
    RenderedBundle rendered =
        bundleOf(
            "partial-suite",
            "1.0.0",
            new RenderedWorkload("Deployment", "first-app", "kind: Deployment\nname: first-app\n"),
            new RenderedWorkload(
                "Deployment", "second-app", "kind: Deployment\nname: second-app\n"),
            new RenderedWorkload("Deployment", "third-app", "kind: Deployment\nname: third-app\n"));
    ControlPlaneApi api = new ControlPlaneApi(fake.address());

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> ReleaseReconciler.deployFresh(api, rendered, false, System.out));
    assertTrue(e.getMessage().contains("third-app"), e.getMessage());

    // the two workloads ahead of the failing one really did apply against the control plane
    assertTrue(fake.hasWorkload("Deployment", "first-app"));
    assertTrue(fake.hasWorkload("Deployment", "second-app"));
    assertFalse(fake.hasWorkload("Deployment", "third-app"));

    // ...and the ledger recorded exactly that, marked FAILED, rather than nothing at all
    Optional<ReleaseRevision> revision = ReleaseLedger.readRevision(api, "partial-suite", 1);
    assertTrue(revision.isPresent(), "a failed deploy must still leave a ledger row behind");
    assertEquals(ReleaseRevisionStatus.FAILED, revision.get().status());
    List<String> appliedNames =
        revision.get().workloads().stream().map(RenderedWorkload::name).toList();
    assertEquals(List.of("first-app", "second-app"), appliedNames);
    assertTrue(ReleaseLedger.readMeta(api, "partial-suite").isPresent());
  }

  @Test
  void upgrade_existing_records_a_failed_revision_with_only_the_workloads_that_actually_applied()
      throws Exception {
    fake = new FakeControlPlane();
    ControlPlaneApi api = new ControlPlaneApi(fake.address());
    RenderedBundle v1 =
        bundleOf(
            "partial-suite",
            "1.0.0",
            new RenderedWorkload("Deployment", "first-app", "kind: Deployment\nname: first-app\n"));
    ReleaseReconciler.deployFresh(api, v1, false, System.out);

    fake.failWorkloadPut("Deployment", "third-app");
    RenderedBundle v2 =
        bundleOf(
            "partial-suite",
            "2.0.0",
            new RenderedWorkload("Deployment", "first-app", "kind: Deployment\nname: first-app\n"),
            new RenderedWorkload(
                "Deployment", "second-app", "kind: Deployment\nname: second-app\n"),
            new RenderedWorkload("Deployment", "third-app", "kind: Deployment\nname: third-app\n"));
    ReleaseMeta meta = ReleaseLedger.readMeta(api, "partial-suite").orElseThrow();

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                ReleaseReconciler.upgradeExisting(
                    api, v2, meta, List.of(), List.of(), false, System.out));
    assertTrue(e.getMessage().contains("third-app"), e.getMessage());

    assertTrue(fake.hasWorkload("Deployment", "second-app"));
    assertFalse(fake.hasWorkload("Deployment", "third-app"));

    Optional<ReleaseRevision> revision = ReleaseLedger.readRevision(api, "partial-suite", 2);
    assertTrue(revision.isPresent(), "a failed upgrade must still leave a new ledger row behind");
    assertEquals(ReleaseRevisionStatus.FAILED, revision.get().status());
    List<String> appliedNames =
        revision.get().workloads().stream().map(RenderedWorkload::name).toList();
    assertEquals(List.of("first-app", "second-app"), appliedNames);

    // meta keeps advancing to the failed attempt's own revision number -- the same way Helm's own
    // release pointer advances on a failed upgrade, it just leaves that revision's status FAILED.
    assertEquals(2, ReleaseLedger.readMeta(api, "partial-suite").orElseThrow().currentRevision());
  }

  @Test
  void a_fully_successful_deploy_still_records_a_succeeded_revision() throws Exception {
    fake = new FakeControlPlane();
    ControlPlaneApi api = new ControlPlaneApi(fake.address());
    RenderedBundle rendered =
        bundleOf(
            "clean-suite",
            "1.0.0",
            new RenderedWorkload("Deployment", "only-app", "kind: Deployment\nname: only-app\n"));

    ReleaseReconciler.deployFresh(api, rendered, false, System.out);

    ReleaseRevision revision = ReleaseLedger.readRevision(api, "clean-suite", 1).orElseThrow();
    assertEquals(ReleaseRevisionStatus.SUCCEEDED, revision.status());
  }

  @Test
  void a_fully_successful_upgrade_still_records_a_succeeded_revision() throws Exception {
    fake = new FakeControlPlane();
    ControlPlaneApi api = new ControlPlaneApi(fake.address());
    RenderedBundle v1 =
        bundleOf(
            "clean-suite",
            "1.0.0",
            new RenderedWorkload("Deployment", "only-app", "kind: Deployment\nname: only-app\n"));
    ReleaseReconciler.deployFresh(api, v1, false, System.out);
    ReleaseMeta meta = ReleaseLedger.readMeta(api, "clean-suite").orElseThrow();

    RenderedBundle v2 =
        bundleOf(
            "clean-suite",
            "2.0.0",
            new RenderedWorkload(
                "Deployment", "only-app", "kind: Deployment\nname: only-app\nreplicas: 2\n"));
    ReleaseReconciler.upgradeExisting(api, v2, meta, List.of(), List.of(), false, System.out);

    ReleaseRevision revision = ReleaseLedger.readRevision(api, "clean-suite", 2).orElseThrow();
    assertEquals(ReleaseRevisionStatus.SUCCEEDED, revision.status());
  }
}
