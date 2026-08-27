package com.gimle.ragnarok.cli;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.fenrir.ChaosLedger;
import com.gimle.ragnarok.fenrir.ChaosPlanParser;
import com.gimle.ragnarok.fenrir.Fenrir;
import com.gimle.ragnarok.fenrir.FenrirPlan;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.endpoint.TargetSpec;
import com.gimle.ragnarok.target.endpoint.TargetSpecParser;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code ragnarok replay --from-report <chaos-report.json> --target <target.yaml>
 * [--confirm-destructive] [--report <dir>]} -- re-runs a previous {@code chaos} run's exact plan
 * and seed for a deterministic repro, reading the fully-resolved plan a {@code chaos --report}
 * embeds inline rather than re-reading the original plan file (which may have moved or changed
 * since). Subject to the identical {@code --confirm-destructive} gate {@code chaos} enforces --
 * replay never bypasses it.
 */
public final class ReplayCommand {

  public static final String USAGE =
      "usage: ragnarok replay --from-report <chaos-report.json> --target <target.yaml>\n"
          + "           [--confirm-destructive] [--report <dir>]";

  private ReplayCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    final Path reportFile = Path.of(CliArgs.requireFlag(args, "--from-report"));
    final TargetSpec spec = TargetSpecParser.resolve(CliArgs.requireFlag(args, "--target"));
    final FenrirPlan plan = planFrom(reportFile);
    ChaosCommand.requireConfirmed(plan, args);
    try (ClusterTarget target = spec.open()) {
      final ChaosLedger ledger = Fenrir.unleash(target, plan);
      out.print(ledger.render());
      CliArgs.optionalFlag(args, "--report")
          .ifPresent(dir -> ChaosCommand.writeReport(ledger, plan, Path.of(dir), out));
      return ledger.allRecovered() ? 0 : 1;
    }
  }

  private static FenrirPlan planFrom(final Path reportFile) {
    final String content;
    try {
      content = Files.readString(reportFile);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed reading chaos report: " + reportFile, e);
    }
    final Object parsed = Json.parse(content);
    if (!(parsed instanceof Map<?, ?> report) || !(report.get("plan") instanceof Map<?, ?> plan)) {
      throw new RagnarokException("report has no embedded plan to replay: " + reportFile);
    }
    return ChaosPlanParser.fromMap(plan);
  }
}
