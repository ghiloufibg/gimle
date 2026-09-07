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
    BundleApplier.applyTenants(api, rendered.tenants());
    BundleApplier.applyConfig(api, rendered.config());
    BundleApplier.applySecrets(api, rendered.secrets());
    BundleApplier.applyWorkloads(api, rendered.workloads());
    BundleApplier.deleteWorkloads(api, toPrune);
    // After the applies, so a key this revision moved between the config store and the vault is
    // written under its new home before the old one is taken away.
    BundleApplier.deleteConfig(api, keysToPrune);
    BundleApplier.deleteSecrets(api, keysToPrune);

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
