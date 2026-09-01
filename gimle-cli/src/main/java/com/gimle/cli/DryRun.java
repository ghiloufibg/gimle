package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code gimle apply --dry-run -f <manifest>}: submits the manifest to the control plane with
 * {@code ?dryRun=true}, which runs authorization, manifest validation, artifact resolution, the
 * admission chain and a placement forecast and then writes nothing, and renders the verdict it
 * sends back.
 *
 * <p><b>Exit code.</b> A dry-run that predicts a rejection exits with the code the real submission
 * would have exited with -- {@link CliExitCode#CONFLICT} for an admission rejection (quota, limit
 * range, a dangling ConfigMap/SecretMap reference), {@link CliExitCode#INVALID_INPUT} for a
 * manifest or artifact problem, {@link CliExitCode#FORBIDDEN} for the reserved-tenant veto -- so
 * {@code gimle apply --dry-run} drops straight into a pipeline as a gate whose exit status means
 * exactly what the unguarded {@code gimle apply} after it would mean. Exiting {@code 0} on a
 * predicted rejection, on the grounds that the preview itself worked, would make the gate useless
 * for the one job it exists to do.
 *
 * <p>An unplaceable replica is the deliberate exception: it is printed as a warning and leaves the
 * exit code at {@code 0}, because no submission is ever refused for being unschedulable -- the
 * replica simply waits for room. Reporting it as a failed command would have the preview disagree
 * with the request it predicts, which is worse than not previewing at all.
 */
final class DryRun {

  private static final String FLAG = "--dry-run";

  private DryRun() {}

  static boolean requested(List<String> args) {
    return args.contains(FLAG);
  }

  static void preview(
      ControlPlaneClient client,
      String path,
      String manifestBody,
      OutputFormat.Kind output,
      PrintStream out,
      PrintStream err) {
    Map<String, Object> verdict =
        Json.asObject(
            Json.parse(client.expectSuccess(client.put(path + "?dryRun=true", manifestBody))));
    if (output == OutputFormat.Kind.JSON) {
      out.println(Json.write(verdict));
    } else {
      printTable(verdict, out);
    }
    warnAboutUnplaceableReplicas(verdict, err);
    if (!Boolean.TRUE.equals(verdict.get("admitted"))) {
      throw rejection(verdict);
    }
  }

  private static void printTable(Map<String, Object> verdict, PrintStream out) {
    out.println(
        "dry run: "
            + verdict.get("kind")
            + "/"
            + verdict.get("name")
            + (verdict.get("tenantId") == null ? "" : " (tenant " + verdict.get("tenantId") + ")"));
    for (Map<String, Object> check : checksOf(verdict)) {
      out.println(check.get("outcome") + "\t" + check.get("name") + "\t" + check.get("detail"));
    }
    boolean admitted = Boolean.TRUE.equals(verdict.get("admitted"));
    out.println(
        admitted
            ? "verdict: would be applied"
            : "verdict: would be rejected (the real request would answer "
                + verdict.get("wouldRespondStatus")
                + ")");
  }

  private static void warnAboutUnplaceableReplicas(Map<String, Object> verdict, PrintStream err) {
    if (!(verdict.get("placement") instanceof Map<?, ?>)) {
      return;
    }
    Map<String, Object> placement = Json.asObject(verdict.get("placement"));
    for (Map<String, Object> failure : Json.asObjectList(placement.get("failures"))) {
      err.println(
          "warning: instance "
              + failure.get("instanceIndex")
              + " would remain unplaced: "
              + failure.get("reason"));
    }
  }

  /**
   * The reason carried out to the caller is the failing check's own detail -- verbatim the message
   * the real submission would have answered with -- not a summary of it.
   */
  private static CliException rejection(Map<String, Object> verdict) {
    List<String> reasons = new ArrayList<>();
    for (Map<String, Object> check : checksOf(verdict)) {
      if ("FAILED".equals(check.get("outcome")) && !"placement".equals(check.get("name"))) {
        reasons.add(String.valueOf(check.get("detail")));
      }
    }
    String message = "dry run: would be rejected: " + String.join("; ", reasons);
    Object status = verdict.get("wouldRespondStatus");
    int code = status instanceof Number number ? number.intValue() : 0;
    return switch (code) {
      case 400 -> CliException.invalidInput(message);
      case 403 -> CliException.forbidden(message);
      case 409 -> CliException.conflict(message);
      default -> new CliException(message);
    };
  }

  private static List<Map<String, Object>> checksOf(Map<String, Object> verdict) {
    return Json.asObjectList(verdict.get("checks"));
  }
}
