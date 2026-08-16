package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code hilmir upgrade -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o
 * json]}: requires an existing release under this bundle's own {@code name}, renders and applies
 * the new bundle's full state the same way {@code deploy} does, then prunes -- any workload the
 * *previous* revision applied that the new bundle no longer declares gets deleted, Helm's own
 * upgrade semantics -- and writes the next revision.
 */
public final class UpgradeCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--wait", "--dry-run");
  private static final Set<String> REPEATABLE_FLAGS = Set.of("--set");

  private UpgradeCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, BOOLEAN_FLAGS, REPEATABLE_FLAGS);
    boolean json = ReleaseOutput.isJson(flags);
    Path bundleFile = Path.of(flags.get("-f"));
    Bundle bundle = DeployCommand.parseBundleFile(bundleFile);
    Map<String, String> values =
        ValueOverrides.merge(bundle.values(), valuesFileFlag(flags), flags.getAll("--set"));
    RenderedBundle rendered =
        BundleRenderer.render(bundle, values, bundleFile.toAbsolutePath().getParent());

    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));
    ReleaseMeta meta =
        ReleaseLedger.readMeta(api, rendered.name())
            .orElseThrow(
                () ->
                    new HilmirException(
                        "no release named '"
                            + rendered.name()
                            + "' -- use 'hilmir deploy' instead"));
    ReleaseRevision previous =
        ReleaseLedger.readRevision(api, rendered.name(), meta.currentRevision())
            .orElseThrow(
                () ->
                    new HilmirException(
                        "release '"
                            + rendered.name()
                            + "' has no revision "
                            + meta.currentRevision()
                            + " recorded in its ledger"));

    List<ResourceRef> currentResources = new ArrayList<>();
    for (RenderedWorkload workload : rendered.workloads()) {
      currentResources.add(new ResourceRef(workload.kind(), workload.name()));
    }
    List<ResourceRef> toPrune =
        previous.resources().stream().filter(r -> !currentResources.contains(r)).toList();

    if (flags.isSet("--dry-run")) {
      ReleasePlan.printWithPrune(rendered, toPrune, json, out);
      return 0;
    }

    BundleApplier.applyTenants(api, rendered.tenants());
    BundleApplier.applyConfig(api, rendered.config());
    BundleApplier.applySecrets(api, rendered.secrets());
    BundleApplier.applyWorkloads(api, rendered.workloads());
    BundleApplier.deleteWorkloads(api, toPrune);
    if (flags.isSet("--wait")) {
      for (RenderedWorkload workload : rendered.workloads()) {
        WaitPoller.awaitReady(api, workload, out);
      }
    }

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
            rendered.secrets(),
            rendered.workloads(),
            Optional.empty()));
    ReleaseLedger.writeMeta(
        api,
        rendered.name(),
        new ReleaseMeta(rendered.name(), rendered.version(), nextRevision, tenantIds));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "upgraded");
    body.put("release", rendered.name());
    body.put("revision", nextRevision);
    body.put("pruned", toPrune.stream().map(ResourceRef::toJson).toList());
    ReleaseOutput.printResult(
        json,
        body,
        "release/"
            + rendered.name()
            + " upgraded (revision "
            + nextRevision
            + "; pruned "
            + toPrune.size()
            + " resource(s))",
        out);
    return 0;
  }

  private static Optional<Path> valuesFileFlag(ReleaseFlags flags) {
    String value = flags.getOrDefault("--values", null);
    return value == null ? Optional.empty() : Optional.of(Path.of(value));
  }
}
