package com.gimle.holmgang.saga;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a {@link SagaCollector}'s accumulated state into the versioned {@code
 * holmgang-report.json} the Saga report console consumes, and writes it under {@code
 * target/holmgang/saga/<run-id>/}. Always written (pass or fail) so a green run's numbers are the
 * baseline the next red run is read against.
 */
final class SagaWriter {

  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private SagaWriter() {}

  static Path write(final SagaCollector c) {
    final Path runDir = Path.of("target", "holmgang", "saga", c.runId());
    final Path file = runDir.resolve("holmgang-report.json");
    try {
      Files.createDirectories(runDir);
      Files.writeString(file, Json.write(report(c)));
    } catch (final IOException e) {
      throw new HolmgangException("failed writing Saga report under " + runDir, e);
    }
    System.out.println("Saga report written to " + file.toAbsolutePath());
    return file;
  }

  static Map<String, Object> report(final SagaCollector c) {
    final long finishedAt = System.currentTimeMillis();
    final List<Map<String, Object>> scenarios = c.allScenarios();
    final boolean anyScenarioFailed =
        scenarios.stream().anyMatch(s -> "FAILED".equals(s.get("status")));
    final boolean anySurtrFailed =
        c.surtrRuns().stream().anyMatch(s -> "FAILED".equals(s.get("outcome")));
    final String outcome = anyScenarioFailed || anySurtrFailed ? "FAILED" : "PASSED";

    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", 1);
    root.put("run", run(c, finishedAt, outcome));
    root.put("topologies", c.topologies());
    root.put("scenarios", scenarios(c, scenarios));
    if (!c.fenrirRuns().isEmpty()) {
      root.put("fenrir", Map.of("runs", c.fenrirRuns()));
    }
    if (!c.surtrRuns().isEmpty()) {
      root.put("surtr", Map.of("runs", c.surtrRuns()));
    }
    return root;
  }

  private static Map<String, Object> run(
      final SagaCollector c, final long finishedAt, final String outcome) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", c.runId());
    run.put("startedAt", ISO.format(Instant.ofEpochMilli(c.startedAtMillis())));
    run.put("finishedAt", ISO.format(Instant.ofEpochMilli(finishedAt)));
    run.put("durationMillis", finishedAt - c.startedAtMillis());
    run.put("outcome", outcome);
    run.put("invocation", invocation());
    run.put("environment", environment());
    return run;
  }

  private static Map<String, Object> scenarios(
      final SagaCollector c, final List<Map<String, Object>> all) {
    int passed = 0;
    int failed = 0;
    int skipped = 0;
    for (final Map<String, Object> s : all) {
      final Object status = s.get("status");
      if ("PASSED".equals(status)) {
        passed++;
      } else if ("FAILED".equals(status)) {
        failed++;
      } else {
        skipped++;
      }
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", all.size());
    out.put("passed", passed);
    out.put("failed", failed);
    out.put("skipped", skipped);
    out.put("features", c.featuresJson());
    return out;
  }

  /**
   * A best-effort reconstruction of the command that produced this run: the base validation
   * invocation plus whichever Surtr/chaos properties are set, since the actual shell line is not
   * available inside the forked JVM.
   */
  private static String invocation() {
    final StringBuilder sb = new StringBuilder("mvn -pl gimle-holmgang verify -Pvalidation");
    appendProp(sb, "gimle.surtr.workload");
    appendProp(sb, "gimle.surtr.scale");
    appendProp(sb, "gimle.holmgang.chaosSeed");
    appendProp(sb, "cucumber.filter.tags");
    return sb.toString();
  }

  private static void appendProp(final StringBuilder sb, final String key) {
    final String value = System.getProperty(key);
    if (value != null && !value.isBlank()) {
      sb.append(" -D").append(key).append('=').append(value);
    }
  }

  private static Map<String, Object> environment() {
    final Map<String, Object> env = new LinkedHashMap<>();
    env.put("jdk", System.getProperty("java.version"));
    env.put(
        "os",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"));
    env.put("host", hostName());
    env.put("gitCommit", System.getProperty("gimle.saga.gitCommit", "unknown"));
    env.put("chaosSeed", longProp("gimle.holmgang.chaosSeed"));
    env.put("surtrScaleFactor", doubleProp("gimle.surtr.scale"));
    return env;
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (final Exception e) {
      return "unknown";
    }
  }

  private static Long longProp(final String key) {
    final String v = System.getProperty(key);
    try {
      return v == null || v.isBlank() ? null : Long.parseLong(v.trim());
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  private static Double doubleProp(final String key) {
    final String v = System.getProperty(key);
    try {
      return v == null || v.isBlank() ? null : Double.parseDouble(v.trim());
    } catch (final NumberFormatException e) {
      return null;
    }
  }
}
