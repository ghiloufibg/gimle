package com.gimle.ragnarok.cli;

import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.endpoint.TargetSpec;
import com.gimle.ragnarok.target.endpoint.TargetSpecParser;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * {@code ragnarok preflight --target <target.yaml>} -- a plain readiness report against a target,
 * no chaos or load: store leader/member visibility, each configured control-plane/Muninn/Andvari
 * endpoint's own reachability. Exits 1 if anything configured is unreachable.
 */
public final class PreflightCommand {

  public static final String USAGE = "usage: ragnarok preflight --target <target.yaml>";

  private PreflightCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    final TargetSpec spec = TargetSpecParser.resolve(CliArgs.requireFlag(args, "--target"));
    boolean ok = true;
    try (ClusterTarget target = spec.open()) {
      ok &= reportStore(spec, target, out);
      ok &= reportControlPlanes(spec, target, out);
      ok &= reportEndpoints("muninn", spec.muninnBaseUrls(), target::muninnServing, out);
      ok &= reportEndpoints("andvari", spec.andvariBaseUrls(), target::andvariServing, out);
    }
    out.println(ok ? "preflight: OK" : "preflight: FAILED");
    return ok ? 0 : 1;
  }

  private static boolean reportStore(
      final TargetSpec spec, final ClusterTarget target, final PrintStream out) {
    if (spec.storeClientEndpoints().isEmpty()) {
      out.println("store: not configured, skipped");
      return true;
    }
    final Optional<String> leader = target.storeLeaderId();
    out.println(
        "store leader: "
            + leader.orElse("UNKNOWN")
            + " ("
            + target.storeMemberIds().size()
            + " members)");
    return leader.isPresent();
  }

  private static boolean reportControlPlanes(
      final TargetSpec spec, final ClusterTarget target, final PrintStream out) {
    boolean ok = true;
    for (int i = 0; i < target.controlPlaneCount(); i++) {
      final boolean serving = target.api(i).isServing();
      out.println(
          "control-plane["
              + i
              + "] "
              + spec.controlPlaneBaseUrls().get(i)
              + ": "
              + status(serving));
      ok &= serving;
    }
    return ok;
  }

  private interface ServingCheck {
    boolean isServing(int index);
  }

  private static boolean reportEndpoints(
      final String name,
      final List<String> baseUrls,
      final ServingCheck check,
      final PrintStream out) {
    boolean ok = true;
    for (int i = 0; i < baseUrls.size(); i++) {
      final boolean serving = check.isServing(i);
      out.println(name + "[" + i + "] " + baseUrls.get(i) + ": " + status(serving));
      ok &= serving;
    }
    return ok;
  }

  private static String status(final boolean serving) {
    return serving ? "OK" : "DOWN";
  }
}
