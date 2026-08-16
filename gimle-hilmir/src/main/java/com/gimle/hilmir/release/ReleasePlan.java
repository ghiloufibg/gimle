package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints a rendered bundle's full apply plan for {@code --dry-run}: what would be created (and, for
 * {@code upgrade}/{@code rollback}, what would be pruned), without making any control-plane call at
 * all for {@code deploy} -- {@code upgrade}/{@code rollback} necessarily read the release's
 * existing ledger state first to compute an accurate prune list, so only their own read calls
 * (never a write) precede this.
 *
 * <p>Public so {@code com.gimle.hilmir.sync} prints a bundle's dry-run plan the same way {@code
 * deploy --dry-run} does -- see {@code SyncCommand}'s own javadoc for why sync's dry-run always
 * prints this unconditioned "would apply" plan rather than a deploy/upgrade/converged verdict.
 */
public final class ReleasePlan {

  private ReleasePlan() {}

  public static void print(RenderedBundle bundle, boolean json, PrintStream out) {
    printWithPrune(bundle, List.of(), json, out);
  }

  static void printWithPrune(
      RenderedBundle bundle, List<ResourceRef> toPrune, boolean json, PrintStream out) {
    Map<String, Object> plan = planMap(bundle);
    plan.put("prune", toPrune.stream().map(ResourceRef::toJson).toList());
    if (json) {
      out.println(Json.write(plan));
      return;
    }
    out.println(
        "dry-run plan for release "
            + bundle.name()
            + " (bundle version "
            + bundle.version()
            + "):");
    out.println("  tenants: " + plan.get("tenants"));
    out.println("  config: " + plan.get("config"));
    out.println("  secrets: " + plan.get("secrets"));
    out.println("  workloads: " + plan.get("workloads"));
    out.println("  prune: " + plan.get("prune"));
  }

  private static Map<String, Object> planMap(RenderedBundle bundle) {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("release", bundle.name());
    plan.put("bundleVersion", bundle.version());
    plan.put("tenants", bundle.tenants().stream().map(ReleasePlan::tenantSummary).toList());
    plan.put("config", bundle.config().stream().map(c -> c.tenant() + "/" + c.key()).toList());
    plan.put("secrets", bundle.secrets().stream().map(s -> s.tenant() + "/" + s.key()).toList());
    plan.put("workloads", bundle.workloads().stream().map(w -> w.kind() + "/" + w.name()).toList());
    return plan;
  }

  private static Map<String, Object> tenantSummary(BundleTenant tenant) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("id", tenant.id());
    summary.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    summary.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    summary.put("maxInstances", tenant.quota().maxInstances());
    return summary;
  }
}
