package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code hilmir rollback --release <name> [--to-revision r] [--wait] [--dry-run] [-o json]}: reads
 * a past revision's full snapshot and re-applies it as a brand-new revision -- never rewrites
 * history in place, matching Helm's own rollback semantics.
 *
 * <p>With no {@code --to-revision}, rolls back to the revision immediately before the release's
 * current one -- the same "undo my last change" default {@code helm rollback} itself uses when
 * given no explicit revision number.
 */
public final class RollbackCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--wait", "--dry-run");

  /**
   * Appended to this verb's own "no such release" failure. {@code rollback} and {@code
   * upgrade-cluster} share nothing but a colloquial meaning: this one re-applies a past revision of
   * a release bundle through the control plane's API, while {@code upgrade-cluster} restarts
   * platform processes onto a different classpath. An operator who has just rolled a platform
   * binary out reaches for "rollback" first, and hilmir keeps no record of the previous classpath
   * to offer them, so the least it can do is say which verb they actually want.
   */
  private static final String NOT_A_PLATFORM_ROLLBACK =
      "\n\nnote: this verb rolls back a release bundle, not a platform binary rollout. To undo an"
          + " `upgrade-cluster`, re-run `upgrade-cluster` with --new-classpath pointing at the"
          + " previous classpath -- hilmir keeps no history of it, so it has to be named"
          + " explicitly.";

  private RollbackCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, BOOLEAN_FLAGS, Set.of());
    boolean json = ReleaseOutput.isJson(flags);
    String releaseName = flags.get("--release");
    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));

    ReleaseMeta meta =
        ReleaseLedger.readMeta(api, releaseName)
            .orElseThrow(
                () ->
                    new HilmirException(
                        "no release named '" + releaseName + "'" + NOT_A_PLATFORM_ROLLBACK));
    int targetRevision = resolveTargetRevision(flags, releaseName, meta);
    ReleaseRevision target =
        ReleaseLedger.readRevision(api, releaseName, targetRevision)
            .orElseThrow(
                () ->
                    new HilmirException(
                        "release '"
                            + releaseName
                            + "' has no revision "
                            + targetRevision
                            + " recorded in its ledger"));
    ReleaseRevision current =
        ReleaseLedger.readRevision(api, releaseName, meta.currentRevision())
            .orElseThrow(
                () ->
                    new HilmirException(
                        "release '"
                            + releaseName
                            + "' has no revision "
                            + meta.currentRevision()
                            + " recorded in its ledger"));

    RenderedBundle asBundle =
        new RenderedBundle(
            releaseName,
            meta.bundleVersion(),
            target.tenants(),
            target.config(),
            // A rollback restores workloads and config; secret material stays with the vault that
            // owns it, which versions every value it has ever held. The ledger records only a
            // digest, so there is nothing here to re-apply and nothing lost by not doing so.
            List.of(),
            target.workloads());
    List<ResourceRef> toPrune =
        current.resources().stream().filter(r -> !target.resources().contains(r)).toList();

    if (flags.isSet("--dry-run")) {
      ReleasePlan.printWithPrune(asBundle, toPrune, json, out);
      return 0;
    }

    BundleApplier.applyTenants(api, target.tenants());
    BundleApplier.applyConfig(api, target.config());
    BundleApplier.applyWorkloads(api, target.workloads());
    BundleApplier.deleteWorkloads(api, toPrune);
    if (flags.isSet("--wait")) {
      for (RenderedWorkload workload : target.workloads()) {
        WaitPoller.awaitReady(api, workload, out);
      }
    }

    int nextRevision = meta.currentRevision() + 1;
    List<String> tenantIds = target.tenants().stream().map(BundleTenant::id).toList();
    ReleaseLedger.writeRevision(
        api,
        releaseName,
        new ReleaseRevision(
            nextRevision,
            Instant.now().toEpochMilli(),
            target.tenants(),
            target.config(),
            target.secrets(),
            target.workloads(),
            Optional.of(targetRevision)));
    ReleaseLedger.writeMeta(
        api,
        releaseName,
        new ReleaseMeta(releaseName, meta.bundleVersion(), nextRevision, tenantIds));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "rolledBack");
    body.put("release", releaseName);
    body.put("toRevision", targetRevision);
    body.put("newRevision", nextRevision);
    ReleaseOutput.printResult(
        json,
        body,
        "release/"
            + releaseName
            + " rolled back to revision "
            + targetRevision
            + " (recorded as new revision "
            + nextRevision
            + ")",
        out);
    return 0;
  }

  private static int resolveTargetRevision(
      ReleaseFlags flags, String releaseName, ReleaseMeta meta) {
    String explicit = flags.getOrDefault("--to-revision", null);
    if (explicit != null) {
      try {
        return Integer.parseInt(explicit);
      } catch (NumberFormatException e) {
        throw new HilmirException("--to-revision must be a number: " + explicit);
      }
    }
    int previousRevision = meta.currentRevision() - 1;
    if (previousRevision < 1) {
      throw new HilmirException(
          "release '"
              + releaseName
              + "' has no revision before its current one ("
              + meta.currentRevision()
              + ") to roll back to; pass --to-revision explicitly");
    }
    return previousRevision;
  }
}
