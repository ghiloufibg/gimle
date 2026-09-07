package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
  public record UpgradeOutcome(int revision, List<ResourceRef> pruned, List<KeyRef> prunedKeys) {}

  /**
   * Applies a brand-new release: tenant, then config, then secrets, then workloads (creating the
   * {@value ReleaseLedger#TENANT} bookkeeping tenant first), an optional per-workload wait, and
   * finally writes revision 1 plus its meta pointer row.
   */
  public static DeployOutcome deployFresh(
      ControlPlaneApi api, RenderedBundle rendered, boolean wait, PrintStream out) {
    ReleaseLedger.ensureTenant(api);

    List<BundleTenant> appliedTenants = new ArrayList<>();
    List<RenderedConfigEntry> appliedConfig = new ArrayList<>();
    List<RenderedSecretEntry> appliedSecrets = new ArrayList<>();
    List<RenderedWorkload> appliedWorkloads = new ArrayList<>();
    try {
      BundleApplier.applyTenants(api, rendered.tenants(), appliedTenants::add);
      BundleApplier.applyConfig(api, rendered.config(), appliedConfig::add);
      BundleApplier.applySecrets(api, rendered.secrets(), appliedSecrets::add);
      BundleApplier.applyWorkloads(api, rendered.workloads(), appliedWorkloads::add);
    } catch (RuntimeException e) {
      // Every apply call above already durably persisted whatever it reached before this one
      // failed -- recording that partial state now, marked FAILED, is what keeps a release with
      // real, running instances from having zero trace in the ledger (Helm's own partial-install
      // behavior: it still writes a failed revision, it just never auto-rolls-back). Recorded
      // before rethrowing for the same reason the successful path records before its own wait:
      // what already happened must never go unrecorded.
      recordFailedRevision(
          api,
          rendered.name(),
          rendered.version(),
          1,
          appliedTenants,
          appliedConfig,
          appliedSecrets,
          appliedWorkloads);
      throw e;
    }

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
            Optional.empty(),
            ReleaseRevisionStatus.SUCCEEDED));
    ReleaseLedger.writeMeta(
        api, rendered.name(), new ReleaseMeta(rendered.name(), rendered.version(), 1, tenantIds));
    awaitIfRequested(api, rendered, wait, out);
    return new DeployOutcome(1);
  }

  /**
   * Writes a revision recording exactly what {@code appliedTenants}/{@code appliedConfig}/{@code
   * appliedSecrets}/{@code appliedWorkloads} actually succeeded before an apply/prune step further
   * along threw, marked {@link ReleaseRevisionStatus#FAILED} -- shared by {@link #deployFresh} and
   * {@link #upgradeExisting}, the two call sites that apply a bundle rather than replay an
   * already-recorded one.
   */
  private static void recordFailedRevision(
      ControlPlaneApi api,
      String releaseName,
      String bundleVersion,
      int revision,
      List<BundleTenant> appliedTenants,
      List<RenderedConfigEntry> appliedConfig,
      List<RenderedSecretEntry> appliedSecrets,
      List<RenderedWorkload> appliedWorkloads) {
    if (appliedTenants.isEmpty()
        && appliedConfig.isEmpty()
        && appliedSecrets.isEmpty()
        && appliedWorkloads.isEmpty()) {
      // Nothing actually reached the control plane -- e.g. a manifest naming a workload kind the
      // platform doesn't even recognize, rejected before the first PUT is ever attempted. There is
      // no live state for a ledger row to be the only record of, so leave the ledger untouched and
      // let the caller's own exception speak for itself.
      return;
    }
    List<String> tenantIds = appliedTenants.stream().map(BundleTenant::id).toList();
    ReleaseLedger.writeRevision(
        api,
        releaseName,
        new ReleaseRevision(
            revision,
            Instant.now().toEpochMilli(),
            List.copyOf(appliedTenants),
            List.copyOf(appliedConfig),
            appliedSecrets.stream().map(SecretRef::of).toList(),
            List.copyOf(appliedWorkloads),
            Optional.empty(),
            ReleaseRevisionStatus.FAILED));
    ReleaseLedger.writeMeta(
        api, releaseName, new ReleaseMeta(releaseName, bundleVersion, revision, tenantIds));
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
   * The config and vault keys {@code previous} applied that {@code rendered} no longer declares.
   *
   * <p>Computed from the bundle as the operator declared it, which is not always the one that gets
   * applied: a caller that withholds a secret whose value it does not currently hold (leaving what
   * the vault already stores) must still pass the declaring bundle here, or the key it deliberately
   * left alone would read as one the release dropped.
   */
  public static List<KeyRef> computeKeyPrune(RenderedBundle rendered, ReleaseRevision previous) {
    List<KeyRef> current = new ArrayList<>();
    for (RenderedConfigEntry entry : rendered.config()) {
      current.add(new KeyRef(entry.tenant(), entry.key()));
    }
    for (RenderedSecretEntry entry : rendered.secrets()) {
      current.add(new KeyRef(entry.tenant(), entry.key()));
    }
    List<KeyRef> stale = new ArrayList<>();
    for (RenderedConfigEntry entry : previous.config()) {
      KeyRef ref = new KeyRef(entry.tenant(), entry.key());
      if (!current.contains(ref)) {
        stale.add(ref);
      }
    }
    for (SecretRef entry : previous.secrets()) {
      KeyRef ref = new KeyRef(entry.tenant(), entry.key());
      if (!current.contains(ref) && !stale.contains(ref)) {
        stale.add(ref);
      }
    }
    return List.copyOf(stale);
  }

  /**
   * Applies an existing release's new content over its previous one: apply, then prune {@code
   * toPrune} and {@code keysToPrune} (see {@link #computePrune} and {@link #computeKeyPrune}), an
   * optional per-workload wait, and finally writes the next revision plus its meta pointer row.
   */
  public static UpgradeOutcome upgradeExisting(
      ControlPlaneApi api,
      RenderedBundle rendered,
      ReleaseMeta meta,
      List<ResourceRef> toPrune,
      List<KeyRef> keysToPrune,
      boolean wait,
      PrintStream out) {
    int nextRevision = meta.currentRevision() + 1;

    List<BundleTenant> appliedTenants = new ArrayList<>();
    List<RenderedConfigEntry> appliedConfig = new ArrayList<>();
    List<RenderedSecretEntry> appliedSecrets = new ArrayList<>();
    List<RenderedWorkload> appliedWorkloads = new ArrayList<>();
    try {
      BundleApplier.applyTenants(api, rendered.tenants(), appliedTenants::add);
      BundleApplier.applyConfig(api, rendered.config(), appliedConfig::add);
      BundleApplier.applySecrets(api, rendered.secrets(), appliedSecrets::add);
      BundleApplier.applyWorkloads(api, rendered.workloads(), appliedWorkloads::add);
      BundleApplier.deleteWorkloads(api, toPrune);
      // After the applies, so a key this revision moved between the config store and the vault is
      // written under its new home before the old one is taken away.
      BundleApplier.deleteConfig(api, keysToPrune);
      BundleApplier.deleteSecrets(api, keysToPrune);
    } catch (RuntimeException e) {
      // See deployFresh's own catch for why this is recorded, not just rethrown: everything
      // gathered above (the new content that did apply, even if the prune that follows it never
      // finished) is already real and live, and must not vanish from the ledger just because this
      // upgrade didn't fully converge.
      recordFailedRevision(
          api,
          rendered.name(),
          rendered.version(),
          nextRevision,
          appliedTenants,
          appliedConfig,
          appliedSecrets,
          appliedWorkloads);
      throw e;
    }

    // Recorded before the wait for the same reason deployFresh does: a timed-out wait must not
    // leave the ledger pointing at the previous revision while the new one is already live.
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
            Optional.empty(),
            ReleaseRevisionStatus.SUCCEEDED));
    ReleaseLedger.writeMeta(
        api,
        rendered.name(),
        new ReleaseMeta(rendered.name(), rendered.version(), nextRevision, tenantIds));
    awaitIfRequested(api, rendered, wait, out);
    return new UpgradeOutcome(nextRevision, toPrune, keysToPrune);
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

  /** One workload's wait outcome, when it didn't succeed. */
  private record WaitFailure(RenderedWorkload workload, RuntimeException cause) {}

  /**
   * Waits on every workload concurrently, one virtual thread each (the same fan-out shape {@link
   * com.gimle.hilmir.remote.RemoteDispatch#dispatch} already uses for its own per-machine
   * dispatch), rather than sequentially: a Job can reach its own terminal state in seconds, and it
   * must be reported the moment it does, not only after every workload ahead of it in the list has
   * already finished waiting up to its own full timeout.
   */
  private static void awaitIfRequested(
      ControlPlaneApi api, RenderedBundle rendered, boolean wait, PrintStream out) {
    if (!wait) {
      return;
    }
    List<WaitFailure> failures = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (RenderedWorkload workload : rendered.workloads()) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      WaitPoller.awaitReady(api, workload, out);
                    } catch (RuntimeException e) {
                      failures.add(new WaitFailure(workload, e));
                    }
                  }));
    }
    awaitAll(threads);
    if (failures.size() == 1) {
      throw failures.get(0).cause();
    }
    if (!failures.isEmpty()) {
      String detail =
          failures.stream()
              .map(
                  f ->
                      f.workload().kind()
                          + " "
                          + f.workload().name()
                          + ": "
                          + f.cause().getMessage())
              .collect(Collectors.joining("; "));
      throw new HilmirException(failures.size() + " workloads failed to become ready: " + detail);
    }
  }

  private static void awaitAll(List<Thread> threads) {
    boolean interrupted = false;
    for (Thread thread : threads) {
      while (true) {
        try {
          thread.join();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
