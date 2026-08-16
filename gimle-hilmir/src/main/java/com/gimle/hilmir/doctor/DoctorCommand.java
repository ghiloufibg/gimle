package com.gimle.hilmir.doctor;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.ArtifactScan;
import com.gimle.hilmir.analyze.ArtifactScanner;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hilmir doctor <jar> [<dep-jar>...] [--vessel] [--server host:port] [--tenant <id>] [-o
 * json]}: runs the full static finding catalog against {@code <jar>} (any further positional jars
 * are extra artifacts on the same hypothetical module path, only consulted by the
 * split-package/bundled-logging-binding checks), plus the cluster-aware checks when {@code
 * --server} is given. Exits non-zero on any {@code ERROR}-severity finding, matching {@code hilmir
 * validate}'s own exit-code convention.
 *
 * <p>{@code --vessel} is the CLI's own signal for "evaluate this jar as vessel-hosting intent,"
 * mirroring how a real deploy manifest's {@code vessel:} block presence/absence is the platform's
 * own switch -- omitted (the default) means module-hosting intent, the richer, more constrained
 * path most callers evaluating a freshly built jar actually want.
 */
public final class DoctorCommand {

  private DoctorCommand() {}

  public static int run(List<String> args, PrintStream out) {
    Args parsed = Args.parse(args);
    boolean json = "json".equals(parsed.output);

    ArtifactScan primary = ArtifactScanner.scan(Path.of(parsed.jars.get(0)));
    List<ArtifactScan> extras = new ArrayList<>();
    for (int i = 1; i < parsed.jars.size(); i++) {
      extras.add(ArtifactScanner.scan(Path.of(parsed.jars.get(i))));
    }

    DoctorHostingIntent intent =
        parsed.vessel ? DoctorHostingIntent.VESSEL : DoctorHostingIntent.MODULE;
    List<DoctorFinding> findings = new ArrayList<>(DoctorAnalyzer.analyze(primary, extras, intent));
    if (parsed.server != null) {
      findings.addAll(runClusterChecks(primary, intent, parsed.server, parsed.tenant));
    }

    boolean hasError = findings.stream().anyMatch(f -> f.severity() == DoctorSeverity.ERROR);
    if (json) {
      printJson(findings, out);
    } else {
      printText(findings, primary.jarPath().toString(), out);
    }
    return hasError ? 1 : 0;
  }

  private static List<DoctorFinding> runClusterChecks(
      ArtifactScan primary, DoctorHostingIntent intent, String server, String tenant) {
    List<DoctorFinding> findings = new ArrayList<>();
    DoctorClusterCheck cluster = new DoctorClusterCheck(server);

    if (intent == DoctorHostingIntent.MODULE
        && primary.descriptor().name().isPresent()
        && primary.descriptor().version().isPresent()) {
      DoctorClusterCheck.RegistryOutcome outcome =
          cluster.checkRegistryCoordinate(
              primary.descriptor().name().get(), primary.descriptor().version().get());
      if (outcome instanceof DoctorClusterCheck.RegistryOutcome.NotFound) {
        findings.add(
            new DoctorFinding(
                "REGISTRY_COORDINATE_NOT_FOUND",
                DoctorSeverity.ERROR,
                "module "
                    + primary.descriptor().name().get()
                    + "@"
                    + primary.descriptor().version().get()
                    + " is not present in the artifact registry behind "
                    + server));
      } else if (outcome instanceof DoctorClusterCheck.RegistryOutcome.Unreachable unreachable) {
        findings.add(
            new DoctorFinding(
                "REGISTRY_UNREACHABLE",
                DoctorSeverity.WARNING,
                "could not confirm the registry coordinate against "
                    + server
                    + ": "
                    + unreachable.reason()));
      }
    }

    if (tenant != null && !cluster.tenantExists(tenant)) {
      findings.add(
          new DoctorFinding(
              "TENANT_NOT_FOUND",
              DoctorSeverity.ERROR,
              "tenant '" + tenant + "' does not exist on the control plane behind " + server));
    }
    return findings;
  }

  private static void printText(List<DoctorFinding> findings, String jarPath, PrintStream out) {
    out.println("hilmir doctor: " + jarPath);
    if (findings.isEmpty()) {
      out.println("  no findings");
      return;
    }
    findings.stream()
        .sorted(Comparator.comparing(f -> severityRank(f.severity())))
        .forEach(f -> out.println("[" + f.severity() + "] " + f.code() + ": " + f.message()));
  }

  private static void printJson(List<DoctorFinding> findings, PrintStream out) {
    List<Object> body = new ArrayList<>();
    for (DoctorFinding f : findings) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("code", f.code());
      entry.put("severity", f.severity().name());
      entry.put("message", f.message());
      body.add(entry);
    }
    out.println(Json.write(body));
  }

  private static int severityRank(DoctorSeverity severity) {
    return switch (severity) {
      case ERROR -> 0;
      case WARNING -> 1;
      case INFO -> 2;
    };
  }

  private static final class Args {
    final List<String> jars = new ArrayList<>();
    boolean vessel;
    String server;
    String tenant;
    String output = "text";

    static Args parse(List<String> args) {
      Args parsed = new Args();
      int i = 0;
      while (i < args.size()) {
        String token = args.get(i);
        switch (token) {
          case "--vessel" -> {
            parsed.vessel = true;
            i++;
          }
          case "--server" -> {
            parsed.server = requireValue(args, i, token);
            i += 2;
          }
          case "--tenant" -> {
            parsed.tenant = requireValue(args, i, token);
            i += 2;
          }
          case "-o" -> {
            parsed.output = requireValue(args, i, token);
            i += 2;
          }
          default -> {
            parsed.jars.add(token);
            i++;
          }
        }
      }
      if (parsed.jars.isEmpty()) {
        throw new HilmirException(
            "usage: hilmir doctor <jar> [<dep-jar>...] [--vessel] [--server "
                + "host:port] [--tenant <id>] [-o json]");
      }
      return parsed;
    }

    private static String requireValue(List<String> args, int i, String flag) {
      if (i + 1 >= args.size()) {
        throw new HilmirException(flag + " requires a value");
      }
      return args.get(i + 1);
    }
  }
}
