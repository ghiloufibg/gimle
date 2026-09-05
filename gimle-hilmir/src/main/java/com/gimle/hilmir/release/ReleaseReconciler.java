package com.gimle.hilmir.release;

import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The apply-to-control-plane core shared by {@code deploy}, {@code upgrade}, {@code undeploy}, and
 * {@code com.gimle.hilmir.sync}: applying a rendered bundle's tenants/config/secrets/workloads,
 * optionally waiting for readiness, and writing the resulting ledger revision + meta -- or, in
 * reverse, tearing a release down entirely. Extracted out of {@link DeployCommand}/{@link
 * UpgradeCommand}/{@link UndeployCommand} so none of those three, nor {@code
 * com.gimle.hilmir.sync}'s own reconcile loop, duplicates this sequencing; each of those classes
 * now only parses its own flags/bundle input, decides which of these methods applies, and turns the
 * returned outcome into its own text/JSON output.
 */
public final class ReleaseReconciler {

  private ReleaseReconciler() {}

  /** The revision written by {@link #deployFresh}. */
  public record DeployOutcome(int revision) {}

  /** The revision written and the resources removed by {@link #upgradeExisting}. */
  public record UpgradeOutcome(int revision, List<ResourceRef> pruned) {}

  /**
   * Applies a brand-new release: tenant, then config, then secrets, then workloads (creating the
   * {@value ReleaseLedger#TENANT} bookkeeping tenant first), an optional per-workload wait, and
   * finally writes revision 1 plus its meta pointer row.
   */
  public static DeployOutcome deployFresh(
      ControlPlaneApi api, RenderedBundle rendered, boolean wait, PrintStream out) {
    ReleaseLedger.ensureTenant(api);
    BundleApplier.applyTenants(api, rendered.tenants());
    BundleApplier.applyConfig(api, rendered.config());
    BundleApplier.applySecrets(api, rendered.secrets());
    BundleApplier.applyWorkloads(api, rendered.workloads());

    // Recorded before the wait, not after: the resources are already applied by this point, so a
    // wait that times out would otherwise leave a live release with no ledger row -- undeployable,
    // because every teardown path finds nothing to tear down. What the ledger records is what was
    // applied; whether it converged is a separate question, and the caller still sees the failure.
    List<String> tenantIds = rendered.tenants().stream().map(BundleTenant::id).toList();
    ReleaseLedger.writeRevision(
        api,
        rendered.name(),
        new ReleaseRevision(
            1,
            Instant.now().toEpochMilli(),
            rendered.tenants(),
            rendered.config(),
            rendered.secrets().stream().map(SecretRef::of).toList(),
            rendered.workloads(),
            Optional.empty()));
    ReleaseLedger.writeMeta(
        api, rendered.name(), new ReleaseMeta(rendered.name(), rendered.version(), 1, tenantIds));
    awaitIfRequested(api, rendered, wait, out);
    return new DeployOutcome(1);
  }

  /**
   * The resources {@code previous} applied that {@code rendered} no longer declares -- Helm's own
   * upgrade-prune semantics. A pure computation, safe to call from a {@code --dry-run} path that
   * has already read the release's existing ledger state but must not apply or write anything.
   */
  public static List<ResourceRef> computePrune(RenderedBundle rendered, ReleaseRevision previous) {
    List<ResourceRef> current = new ArrayList<>();
    for (RenderedWorkload workload : rendered.workloads()) {
      current.add(new ResourceRef(workload.kind(), workload.name()));
    }
    return previous.resources().stream().filter(r -> !current.contains(r)).toList();
  }

  /**
   * Applies an existing release's new content over its previous one: apply, then prune {@code
   * toPrune} (see {@link #computePrune}), an optional per-workload wait, and finally writes the
   * next revision plus its meta pointer row.
   */
  public static UpgradeOutcome upgradeExisting(
      ControlPlaneApi api,
      RenderedBundle rendered,
      ReleaseMeta meta,
      List<ResourceRef> toPrune,
      boolean wait,
      PrintStream out) {
    BundleApplier.applyTenants(api, rendered.tenants());
    BundleApplier.applyConfig(api, rendered.config());
    BundleApplier.applySecrets(api, rendered.secrets());
    BundleApplier.applyWorkloads(api, rendered.workloads());
    BundleApplier.deleteWorkloads(api, toPrune);

    // Recorded before the wait for the same reason deployFresh does: a timed-out wait must not
    // leave the ledger pointing at the previous revision while the new one is already live.
    int nextRevision = meta.currentRevision() + 1;
    List<String> tenantIds = rendered.tenants().stream().map(BundleTenant::id).toList();
    ReleaseLedger.writeRevision(
        api,
        rendered.name(),
        new ReleaseRevision(
            nextRevision,
            Instant.now().toEpochMilli(),
            rendered.tenants(),
            rendered.config(),
            rendered.secrets().stream().map(SecretRef::of).toList(),
            rendered.workloads(),
            Optional.empty()));
    ReleaseLedger.writeMeta(
        api,
        rendered.name(),
        new ReleaseMeta(rendered.name(), rendered.version(), nextRevision, tenantIds));
    awaitIfRequested(api, rendered, wait, out);
    return new UpgradeOutcome(nextRevision, toPrune);
  }

  /**
   * Tears a release down entirely: deletes {@code current}'s workloads in reverse apply order,
   * deletes the tenants {@code meta} recorded unless {@code keepTenants}, then deletes every ledger
   * row -- every {@code .rev.<n>} row and finally the {@code .meta} row itself. Shared by {@link
   * UndeployCommand} (an operator-requested undeploy) and {@code com.gimle.hilmir.sync}'s own
   * {@code --prune} (an orphaned release no bundle in this run declares any more) -- the end state
   * is identical either way, only who decided to trigger it differs.
   */
  public static void undeployRelease(
      ControlPlaneApi api,
      String releaseName,
      ReleaseMeta meta,
      ReleaseRevision current,
      boolean keepTenants) {
    List<ResourceRef> reverseOrder = new ArrayList<>(current.resources());
    Collections.reverse(reverseOrder);
    BundleApplier.deleteWorkloads(api, reverseOrder);

    if (!keepTenants) {
      BundleApplier.deleteTenants(api, meta.tenants());
    }

    for (int revision : ReleaseLedger.listRevisions(api, releaseName)) {
      ReleaseLedger.deleteRevision(api, releaseName, revision);
    }
    ReleaseLedger.deleteMeta(api, releaseName);
  }

  private static void awaitIfRequested(
      ControlPlaneApi api, RenderedBundle rendered, boolean wait, PrintStream out) {
    if (!wait) {
      return;
    }
    for (RenderedWorkload workload : rendered.workloads()) {
      WaitPoller.awaitReady(api, workload, out);
    }
  }
}
