package com.gimle.hilmir.sync;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.release.Bundle;
import com.gimle.hilmir.release.BundleParser;
import com.gimle.hilmir.release.BundleRenderer;
import com.gimle.hilmir.release.ControlPlaneApi;
import com.gimle.hilmir.release.KeyRef;
import com.gimle.hilmir.release.ReleaseFlags;
import com.gimle.hilmir.release.ReleaseLedger;
import com.gimle.hilmir.release.ReleaseMeta;
import com.gimle.hilmir.release.ReleaseOutput;
import com.gimle.hilmir.release.ReleasePlan;
import com.gimle.hilmir.release.ReleaseReconciler;
import com.gimle.hilmir.release.ReleaseRevision;
import com.gimle.hilmir.release.ReleaseServer;
import com.gimle.hilmir.release.RenderedBundle;
import com.gimle.hilmir.release.ResourceRef;
import com.gimle.hilmir.release.ValueOverrides;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code hilmir sync (-f <bundle.yaml> | --dir <directory>) [--values <file>] [--set k=v]...
 * [--wait] [--dry-run] [--prune] [-o json] [--server host:port] [--watch <seconds>]}: a one-shot
 * GitOps-style reconciler over bundle files already sitting on local disk -- render each bundle,
 * compare it against the release ledger's own recorded content, and only touch the control plane
 * for a bundle that is actually new or actually changed.
 *
 * <p>Deliberately one-shot, matching every other hilmir verb's own posture ("act, print, exit"):
 * there is no foreground watch loop by default. Watching a git checkout for new commits is
 * explicitly out of scope here -- that's a shell loop around this command ({@code while true; do
 * git pull && hilmir sync --dir ./checkout; sleep 30; done}), not something this verb builds in.
 * {@code --watch <seconds>} exists only as a thin, optional convenience wrapper around the
 * identical one-shot pass (sleep, repeat, run in the foreground until interrupted); it is not the
 * load-bearing design here and carries no automated test of its own; unlike everything else this
 * command does.
 *
 * <p>Per bundle, against the release ledger written by {@code deploy}/{@code upgrade}:
 *
 * <ul>
 *   <li>no {@code .meta} row for this bundle's name -- a fresh release, applied via {@link
 *       ReleaseReconciler#deployFresh}.
 *   <li>a {@code .meta} row exists and its current revision's content ({@link
 *       ReleaseRevision#matchesContent}) already equals the freshly rendered bundle -- already
 *       converged, nothing is applied and no revision is written. This is the behavior {@code
 *       upgrade} itself deliberately does not have (it always bumps the revision, even for
 *       identical content) -- {@code sync} adds the diff so a re-run over unchanged bundle files is
 *       a true no-op, without changing {@code upgrade}'s own existing semantics.
 *   <li>a {@code .meta} row exists and the content differs -- applied via {@link
 *       ReleaseReconciler#upgradeExisting}, including its usual intra-revision resource prune.
 * </ul>
 *
 * <p>A parse failure (malformed YAML/bundle document) aborts the whole invocation immediately, the
 * same as a single bad {@code -f} file already does for {@code deploy}/{@code upgrade} -- there is
 * no bundle name yet to report a per-bundle failure against, and a directory containing one corrupt
 * file is a configuration problem, not a transient reconciliation failure. Once a bundle has
 * parsed, everything past that point (rendering, reading the ledger, applying) is caught per bundle
 * in {@code --dir} mode: one bundle's render error or control-plane rejection is recorded as a
 * failure and reconciliation continues with the rest, rather than aborting a whole directory's
 * worth of otherwise healthy releases over one broken one -- the more GitOps-appropriate posture
 * for a multi-bundle reconcile pass. The run's exit code is nonzero if any bundle failed.
 *
 * <p>{@code --dry-run} makes zero control-plane calls -- not even the ledger read {@code sync}
 * otherwise needs to tell deploy/upgrade/already-converged apart, mirroring {@code deploy
 * --dry-run}'s own "nothing to check against without a call" reasoning. Consequently a sync dry-run
 * cannot report which category a bundle falls into; it prints the same "this is what would be
 * applied" plan {@link ReleasePlan#print} already renders for {@code deploy --dry-run}, for every
 * bundle uniformly.
 *
 * <p>{@code --prune} additionally removes ("garbage collects") a release whose ledger {@code .meta}
 * row exists but whose bundle name is not declared by any bundle in this invocation -- true
 * orphan-removal GitOps semantics, distinct from (and layered on top of) the intra-revision
 * resource prune {@code upgradeExisting} always performs for a changed bundle. It requires {@code
 * --dir} (comparing "everything on the ledger" against "one single file" is rarely what an operator
 * means, and would make a plain {@code -f} sync capable of deleting every other release in the
 * cluster) and has no effect under {@code --dry-run}, for the same zero-API-calls reason above.
 */
public final class SyncCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--wait", "--dry-run", "--prune");
  private static final Set<String> REPEATABLE_FLAGS = Set.of("--set");

  private SyncCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, BOOLEAN_FLAGS, REPEATABLE_FLAGS);
    validateFlags(flags);

    String watch = flags.getOrDefault("--watch", null);
    if (watch == null) {
      return runOnce(flags, out);
    }
    int intervalSeconds = parseWatchInterval(watch);
    int lastExitCode = 0;
    while (true) {
      lastExitCode = runOnce(flags, out);
      out.println("sync: next pass in " + intervalSeconds + "s (--watch)");
      try {
        Thread.sleep(Duration.ofSeconds(intervalSeconds));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return lastExitCode;
      }
    }
  }

  private static void validateFlags(ReleaseFlags flags) {
    boolean hasFile = flags.getOrDefault("-f", null) != null;
    boolean hasDir = flags.getOrDefault("--dir", null) != null;
    if (hasFile == hasDir) {
      throw new HilmirException("exactly one of -f <bundle.yaml> or --dir <directory> is required");
    }
    if (flags.isSet("--prune") && !hasDir) {
      throw new HilmirException(
          "--prune requires --dir -- it compares the full release ledger against every bundle in a"
              + " directory, not a single file");
    }
  }

  private static int runOnce(ReleaseFlags flags, PrintStream out) {
    boolean json = ReleaseOutput.isJson(flags);
    boolean dryRun = flags.isSet("--dry-run");
    boolean wait = flags.isSet("--wait");
    boolean prune = flags.isSet("--prune");

    List<BundleSource> sources = parseAll(collectBundleFiles(flags));
    checkNoDuplicateNames(sources);

    Optional<Path> valuesFile = valuesFileFlag(flags);
    List<String> setFlags = flags.getAll("--set");
    // --dry-run resolves no server and constructs no ControlPlaneApi at all -- there is nothing to
    // check a bundle's ledger state against without a call, the same reasoning DeployCommand's own
    // dry-run already documents.
    ControlPlaneApi api = dryRun ? null : new ControlPlaneApi(ReleaseServer.resolve(flags));

    List<Map<String, Object>> results = new ArrayList<>();
    for (BundleSource source : sources) {
      results.add(reconcileOne(source, valuesFile, setFlags, api, dryRun, wait, json, out));
    }

    if (prune && !dryRun) {
      Set<String> declared =
          sources.stream()
              .map(s -> s.bundle().name())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      for (String orphan : orphanReleaseNames(api, declared)) {
        results.add(pruneOrphan(api, orphan, json, out));
      }
    }

    printSummary(results, sources.size(), json, out);
    boolean anyFailed = results.stream().anyMatch(r -> "failed".equals(r.get("result")));
    return anyFailed ? 1 : 0;
  }

  // -- per-bundle reconciliation --------------------------------------------------------------

  private record BundleSource(Path file, Bundle bundle) {}

  private static Map<String, Object> reconcileOne(
      BundleSource source,
      Optional<Path> valuesFile,
      List<String> setFlags,
      ControlPlaneApi api,
      boolean dryRun,
      boolean wait,
      boolean json,
      PrintStream out) {
    String bundleName = source.bundle().name();
    String file = source.file().toString();
    try {
      Map<String, String> values =
          ValueOverrides.merge(source.bundle().values(), valuesFile, setFlags);
      RenderedBundle rendered =
          BundleRenderer.render(
              source.bundle(), values, source.file().toAbsolutePath().getParent());

      if (dryRun) {
        ReleasePlan.print(rendered, json, out);
        return resultBody("planned", bundleName, file);
      }

      Optional<ReleaseMeta> metaOpt = ReleaseLedger.readMeta(api, rendered.name());
      if (metaOpt.isEmpty()) {
        return reportDeployed(api, rendered, wait, json, out, file);
      }
      return reconcileExisting(api, rendered, metaOpt.get(), wait, json, out, file);
    } catch (RuntimeException e) {
      Map<String, Object> body = resultBody("failed", bundleName, file);
      body.put("error", e.getMessage());
      ReleaseOutput.printResult(
          json, body, "release/" + bundleName + " FAILED: " + e.getMessage(), out);
      return body;
    }
  }

  private static Map<String, Object> reportDeployed(
      ControlPlaneApi api,
      RenderedBundle rendered,
      boolean wait,
      boolean json,
      PrintStream out,
      String file) {
    ReleaseReconciler.DeployOutcome outcome =
        ReleaseReconciler.deployFresh(api, rendered, wait, out);
    Map<String, Object> body = resultBody("deployed", rendered.name(), file);
    body.put("revision", outcome.revision());
    ReleaseOutput.printResult(
        json,
        body,
        "release/" + rendered.name() + " deployed (revision " + outcome.revision() + ")",
        out);
    return body;
  }

  private static Map<String, Object> reconcileExisting(
      ControlPlaneApi api,
      RenderedBundle rendered,
      ReleaseMeta meta,
      boolean wait,
      boolean json,
      PrintStream out,
      String file) {
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

    if (previous.matchesContent(rendered)) {
      Map<String, Object> body = resultBody("already-converged", rendered.name(), file);
      body.put("revision", meta.currentRevision());
      ReleaseOutput.printResult(
          json,
          body,
          "release/"
              + rendered.name()
              + " already converged (revision "
              + meta.currentRevision()
              + "), nothing to do",
          out);
      return body;
    }

    List<ResourceRef> toPrune = ReleaseReconciler.computePrune(rendered, previous);
    List<KeyRef> keysToPrune = ReleaseReconciler.computeKeyPrune(rendered, previous);
    ReleaseReconciler.UpgradeOutcome outcome =
        ReleaseReconciler.upgradeExisting(api, rendered, meta, toPrune, keysToPrune, wait, out);
    Map<String, Object> body = resultBody("upgraded", rendered.name(), file);
    body.put("revision", outcome.revision());
    body.put("pruned", outcome.pruned().stream().map(ResourceRef::toJson).toList());
    body.put("prunedKeys", outcome.prunedKeys().stream().map(KeyRef::toJson).toList());
    ReleaseOutput.printResult(
        json,
        body,
        "release/"
            + rendered.name()
            + " upgraded (revision "
            + outcome.revision()
            + "; pruned "
            + outcome.pruned().size()
            + " resource(s))",
        out);
    return body;
  }

  // -- orphan pruning (--prune) ----------------------------------------------------------------

  private static List<String> orphanReleaseNames(ControlPlaneApi api, Set<String> declared) {
    List<String> orphans = new ArrayList<>();
    for (String name : ReleaseLedger.listReleaseNames(api)) {
      if (!declared.contains(name)) {
        orphans.add(name);
      }
    }
    return orphans;
  }

  private static Map<String, Object> pruneOrphan(
      ControlPlaneApi api, String releaseName, boolean json, PrintStream out) {
    try {
      ReleaseMeta meta =
          ReleaseLedger.readMeta(api, releaseName)
              .orElseThrow(
                  () ->
                      new HilmirException(
                          "release '" + releaseName + "' vanished from the ledger mid-prune"));
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
      ReleaseReconciler.undeployRelease(api, releaseName, meta, current, false);
      Map<String, Object> body = resultBody("pruned", releaseName, null);
      ReleaseOutput.printResult(
          json,
          body,
          "release/" + releaseName + " pruned (no synced bundle declares it any more)",
          out);
      return body;
    } catch (RuntimeException e) {
      Map<String, Object> body = resultBody("failed", releaseName, null);
      body.put("error", e.getMessage());
      ReleaseOutput.printResult(
          json, body, "release/" + releaseName + " prune FAILED: " + e.getMessage(), out);
      return body;
    }
  }

  // -- bundle file discovery --------------------------------------------------------------------

  private static List<Path> collectBundleFiles(ReleaseFlags flags) {
    String dirValue = flags.getOrDefault("--dir", null);
    if (dirValue == null) {
      return List.of(Path.of(flags.get("-f")));
    }
    Path dir = Path.of(dirValue);
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(SyncCommand::isBundleFile)
          .sorted(Comparator.comparing(SyncCommand::fileName))
          .toList();
    } catch (IOException e) {
      throw new HilmirException(
          "could not scan bundle directory " + dir + ": " + e.getMessage(), e);
    }
  }

  /** Non-recursive by design -- a nested directory's own bundles need their own {@code --dir}. */
  private static boolean isBundleFile(Path path) {
    String name = fileName(path);
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  // Files.list(dir)'s own entries are always direct children of dir, so getFileName() is never
  // actually null here -- the empty-string fallback keeps this total rather than throwing on that
  // theoretical case, the same posture InitCommand's own jar-name guessing already takes.
  private static String fileName(Path path) {
    Path fileNamePath = path.getFileName();
    return fileNamePath == null ? "" : fileNamePath.toString();
  }

  private static List<BundleSource> parseAll(List<Path> files) {
    List<BundleSource> sources = new ArrayList<>();
    for (Path file : files) {
      sources.add(new BundleSource(file, parseBundleFile(file)));
    }
    return sources;
  }

  private static Bundle parseBundleFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return BundleParser.parse(in);
    } catch (IOException e) {
      throw new HilmirException("failed reading bundle file " + file + ": " + e.getMessage(), e);
    }
  }

  private static void checkNoDuplicateNames(List<BundleSource> sources) {
    Map<String, Path> seen = new LinkedHashMap<>();
    for (BundleSource source : sources) {
      Path prior = seen.putIfAbsent(source.bundle().name(), source.file());
      if (prior != null) {
        throw new HilmirException(
            "bundle name '"
                + source.bundle().name()
                + "' is declared by both "
                + prior
                + " and "
                + source.file());
      }
    }
  }

  private static Optional<Path> valuesFileFlag(ReleaseFlags flags) {
    String value = flags.getOrDefault("--values", null);
    return value == null ? Optional.empty() : Optional.of(Path.of(value));
  }

  private static int parseWatchInterval(String raw) {
    int seconds;
    try {
      seconds = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new HilmirException(
          "--watch requires a positive integer number of seconds, got: " + raw);
    }
    if (seconds <= 0) {
      throw new HilmirException(
          "--watch requires a positive integer number of seconds, got: " + raw);
    }
    return seconds;
  }

  // -- output ------------------------------------------------------------------------------------

  private static Map<String, Object> resultBody(String result, String releaseName, String file) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("release", releaseName);
    if (file != null) {
      body.put("file", file);
    }
    return body;
  }

  private static void printSummary(
      List<Map<String, Object>> results, int bundleCount, boolean json, PrintStream out) {
    Map<String, Long> counts =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    r -> (String) r.get("result"), LinkedHashMap::new, Collectors.counting()));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("result", "sync-summary");
    summary.put("bundles", bundleCount);
    summary.put("counts", counts);
    boolean anyFailed = counts.getOrDefault("failed", 0L) > 0;
    String text =
        "sync complete: "
            + bundleCount
            + " bundle(s) -- "
            + humanCounts(counts)
            + (anyFailed ? " (see errors above)" : "");
    ReleaseOutput.printResult(json, summary, text, out);
  }

  private static String humanCounts(Map<String, Long> counts) {
    List<String> order =
        List.of("deployed", "upgraded", "already-converged", "pruned", "planned", "failed");
    List<String> parts = new ArrayList<>();
    for (String key : order) {
      long n = counts.getOrDefault(key, 0L);
      if (n > 0) {
        parts.add(n + " " + key.replace('-', ' '));
      }
    }
    return parts.isEmpty() ? "nothing to do" : String.join(", ", parts);
  }
}
