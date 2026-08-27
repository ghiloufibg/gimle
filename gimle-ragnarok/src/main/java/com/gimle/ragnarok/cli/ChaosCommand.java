package com.gimle.ragnarok.cli;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.fenrir.ChaosLedger;
import com.gimle.ragnarok.fenrir.ChaosPlanParser;
import com.gimle.ragnarok.fenrir.FaultKind;
import com.gimle.ragnarok.fenrir.Fenrir;
import com.gimle.ragnarok.fenrir.FenrirPlan;
import com.gimle.ragnarok.fenrir.Pool;
import com.gimle.ragnarok.target.endpoint.EndpointClusterTarget;
import com.gimle.ragnarok.target.endpoint.TargetSpec;
import com.gimle.ragnarok.target.endpoint.TargetSpecParser;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ragnarok chaos --target <target.yaml> --plan <plan.yaml> [--seed N]
 * [--confirm-destructive] [--report <dir>]} -- runs a {@link FenrirPlan} against a real target.
 *
 * <p>The safety gate: a plan whose pools include anything beyond a pure network fault (killing or
 * bouncing a real process) is refused unless {@code --confirm-destructive} is passed. This checks
 * the plan's own declared pools, not what the target can currently fire -- {@link
 * EndpointClusterTarget} today has no process control at all (every fault beyond {@link
 * FaultKind#LINK_CUT}/{@link FaultKind#STORE_PARTITION} always records {@code SKIPPED}), but the
 * gate is meant to hold for whatever {@link com.gimle.ragnarok.target.ClusterTarget} a future,
 * process-control-capable adapter provides too.
 */
public final class ChaosCommand {

  public static final String USAGE =
      "usage: ragnarok chaos --target <target.yaml> --plan <plan.yaml> [--seed N]\n"
          + "           [--confirm-destructive] [--report <dir>]";

  private static final Set<FaultKind> NON_DESTRUCTIVE =
      EnumSet.of(FaultKind.LINK_CUT, FaultKind.STORE_PARTITION);

  private static final String REPORT_FILE_NAME = "chaos-report.json";

  private ChaosCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    final TargetSpec spec = TargetSpecParser.resolve(CliArgs.requireFlag(args, "--target"));
    FenrirPlan plan = ChaosPlanParser.resolve(CliArgs.requireFlag(args, "--plan"));
    plan = withSeedOverride(plan, args);
    requireConfirmed(plan, args);
    try (EndpointClusterTarget target = spec.open()) {
      final ChaosLedger ledger = Fenrir.unleash(target, plan);
      out.print(ledger.render());
      final FenrirPlan finalPlan = plan;
      CliArgs.optionalFlag(args, "--report")
          .ifPresent(dir -> writeReport(ledger, finalPlan, Path.of(dir), out));
      return ledger.allRecovered() ? 0 : 1;
    }
  }

  /**
   * Refuses to run a plan whose pools include a destructive fault kind unless the operator passed
   * {@code --confirm-destructive}. Shared with {@code ReplayCommand}: replaying a destructive plan
   * needs the same confirmation, not a bypass.
   */
  static void requireConfirmed(final FenrirPlan plan, final List<String> args) {
    if (CliArgs.flagPresent(args, "--confirm-destructive")) {
      return;
    }
    final List<FaultKind> destructive =
        plan.pools().stream()
            .map(Pool::kind)
            .filter(kind -> !NON_DESTRUCTIVE.contains(kind))
            .toList();
    if (!destructive.isEmpty()) {
      throw new RagnarokException(
          "plan declares destructive fault kind(s) "
              + destructive
              + " -- pass --confirm-destructive to run it (network-only faults, "
              + NON_DESTRUCTIVE
              + ", never require this flag)");
    }
  }

  /** Shared with {@code ReplayCommand}, so a replay's own report is written the identical way. */
  static void writeReport(
      final ChaosLedger ledger, final FenrirPlan plan, final Path dir, final PrintStream out) {
    final Map<String, Object> report = new LinkedHashMap<>(ledger.toJsonMap());
    report.put("plan", plan.toJsonMap());
    try {
      Files.createDirectories(dir);
      final Path file = dir.resolve(REPORT_FILE_NAME);
      Files.writeString(file, Json.write(report));
      out.println("wrote " + file);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed writing chaos report under " + dir, e);
    }
  }

  private static FenrirPlan withSeedOverride(final FenrirPlan plan, final List<String> args) {
    final var seed = CliArgs.optionalLongFlag(args, "--seed");
    if (seed.isEmpty()) {
      return plan;
    }
    final FenrirPlan.Builder builder =
        FenrirPlan.seeded(seed.get())
            .soakFor(plan.soak())
            .strikeEvery(plan.gapMin(), plan.gapMax())
            .convergeBetweenFaults(plan.convergeBetweenFaults())
            .gateTimeout(plan.gateTimeout());
    if (!plan.eligibleDeployments().isEmpty()) {
      builder.eligibleDeployments(plan.eligibleDeployments().toArray(new String[0]));
    }
    plan.pools().forEach(builder::pool);
    return builder.build();
  }
}
