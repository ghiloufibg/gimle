package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code hilmir undeploy --release <name> [--keep-tenants] [-o json]}: deletes every workload the
 * release's current revision lists (reverse of apply order), deletes the tenants it created unless
 * {@code --keep-tenants} is given, then deletes the release's own ledger rows entirely -- {@code
 * .meta} and every {@code .rev.<n>} row.
 *
 * <p>This is a documented v1 simplification versus Helm's own {@code --keep-history}: once
 * undeployed, a release's revision history is gone, not merely hidden -- there is no {@code helm
 * history}-equivalent read path for a release that no longer has a {@code .meta} row pointing at
 * it, so keeping the {@code .rev.*} rows around after undeploy would only be dead, unreachable
 * data.
 */
public final class UndeployCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--keep-tenants");

  private UndeployCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, BOOLEAN_FLAGS, Set.of());
    boolean json = ReleaseOutput.isJson(flags);
    String releaseName = flags.get("--release");
    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));

    ReleaseMeta meta =
        ReleaseLedger.readMeta(api, releaseName)
            .orElseThrow(() -> new HilmirException("no release named '" + releaseName + "'"));
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

    ReleaseReconciler.undeployRelease(
        api, releaseName, meta, current, flags.isSet("--keep-tenants"));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "undeployed");
    body.put("release", releaseName);
    body.put("keptTenants", flags.isSet("--keep-tenants"));
    ReleaseOutput.printResult(json, body, "release/" + releaseName + " undeployed", out);
    return 0;
  }
}
