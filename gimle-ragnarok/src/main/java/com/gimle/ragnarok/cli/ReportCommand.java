package com.gimle.ragnarok.cli;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.RagnarokException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code ragnarok report (--chaos-report <path> | --surtr-report <dir>)} -- an offline
 * pretty-printer for a report a previous {@code chaos}/{@code stress} run already wrote to disk. No
 * re-run, no target needed: reads the JSON straight off the filesystem, useful for a CI artifact or
 * sharing a result without cluster access.
 */
public final class ReportCommand {

  public static final String USAGE =
      "usage: ragnarok report (--chaos-report <path> | --surtr-report <dir>)";

  private ReportCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    final var chaosReport = CliArgs.optionalFlag(args, "--chaos-report");
    final var surtrReport = CliArgs.optionalFlag(args, "--surtr-report");
    if (chaosReport.isPresent() == surtrReport.isPresent()) {
      throw new RagnarokException(
          "exactly one of --chaos-report <path> or --surtr-report <dir> is required");
    }
    if (chaosReport.isPresent()) {
      return renderChaosReport(Path.of(chaosReport.get()), out);
    }
    return renderSurtrReport(Path.of(surtrReport.get()).resolve("summary.json"), out);
  }

  private static int renderChaosReport(final Path file, final PrintStream out) {
    final Map<String, Object> report = readJsonObject(file);
    out.println(
        "Fenrir chaos report (seed "
            + report.get("seed")
            + "): "
            + report.get("executed")
            + " executed, "
            + report.get("recovered")
            + " recovered, "
            + report.get("skipped")
            + " skipped");
    for (final Object entry : Json.asArray(report.getOrDefault("entries", List.of()))) {
      final Map<String, Object> row = Json.asObject(entry);
      out.println(
          "  #"
              + row.get("index")
              + " "
              + row.get("kind")
              + " t+"
              + row.get("firedAtOffsetMillis")
              + "ms "
              + row.get("outcome")
              + (row.get("victim") != null ? " victim=" + row.get("victim") : "")
              + (row.get("skipReason") != null ? " (" + row.get("skipReason") + ")" : ""));
    }
    // The report has no standalone "allRecovered" field (see ChaosLedger#toJsonMap) -- derived
    // here instead, matching ChaosLedger#allRecovered's own definition: recovered == executed.
    return report.get("executed").equals(report.get("recovered")) ? 0 : 1;
  }

  private static boolean boolFrom(final Object value) {
    return value instanceof Boolean b && b;
  }

  private static int renderSurtrReport(final Path file, final PrintStream out) {
    final Map<String, Object> report = readJsonObject(file);
    final boolean passed = boolFrom(report.get("passed"));
    out.println(
        "Surtr workload '"
            + report.get("workload")
            + "' on "
            + report.get("topology")
            + " -> "
            + (passed ? "PASSED" : "FAILED"));
    for (final Object gate : Json.asArray(report.getOrDefault("gates", List.of()))) {
      final Map<String, Object> row = Json.asObject(gate);
      out.println(
          "  gate "
              + row.get("name")
              + " "
              + (boolFrom(row.get("passed")) ? "PASS" : "FAIL")
              + " observed="
              + row.get("observed")
              + " threshold="
              + row.get("threshold"));
    }
    return passed ? 0 : 1;
  }

  private static Map<String, Object> readJsonObject(final Path file) {
    try {
      return Json.asObject(Json.parse(Files.readString(file)));
    } catch (final IOException e) {
      throw new UncheckedIOException("failed reading report: " + file, e);
    }
  }
}
