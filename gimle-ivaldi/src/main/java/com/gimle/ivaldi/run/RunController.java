package com.gimle.ivaldi.run;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.protocol.Json;
import com.gimle.hilmir.launch.MachineLauncher;
import com.gimle.hilmir.launch.PkiInit;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.release.Bundle;
import com.gimle.hilmir.release.BundleParser;
import com.gimle.hilmir.release.BundleRenderer;
import com.gimle.hilmir.release.ControlPlaneApi;
import com.gimle.hilmir.release.ReleaseLedger;
import com.gimle.hilmir.release.ReleaseMeta;
import com.gimle.hilmir.release.ReleaseReconciler;
import com.gimle.hilmir.release.RenderedBundle;
import com.gimle.hilmir.release.ResourceRef;
import com.gimle.hilmir.release.ValueOverrides;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.topology.Transport;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.validate.FileSetValidator;
import com.gimle.ivaldi.validate.Finding;
import com.gimle.ivaldi.validate.RenderedFile;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Runs a Blueprint against a saved cluster connection, in-process -- no {@code hilmir} subprocess.
 * Every step below is a small, already-public piece of {@code gimle-hilmir}'s own release/launch
 * machinery, called directly: {@link MachineLauncher#up}/{@link MachineLauncher#down} for the
 * platform process tree, {@link BundleRenderer}/{@link ReleaseReconciler} for the application
 * deploy, {@link ControlPlaneApi#putFile} for jar-sourced artifact pushes. One run at a time: a
 * second {@link #start} while one is {@linkplain RunStatus#isInFlight() in flight} is refused.
 *
 * <h2>Deploy-only vs. reboot</h2>
 *
 * <p>{@link ClusterStore} keeps the {@code topology.yaml} text a run last actually applied to each
 * cluster. A new run compares its own rendered topology against that text (see {@link
 * #normalizeTopology}): identical means nothing about the platform changed, so {@code
 * MachineLauncher.up}/{@code down} are skipped entirely and only the bundle is (re)applied onto the
 * already-running processes; different (or nothing recorded yet) means a real reboot -- tearing the
 * previous process tree down first when one was recorded, then booting the new one -- before the
 * bundle is applied to the fresh cluster.
 *
 * <h2>Known limits</h2>
 *
 * <p>A cluster's transport (plaintext or mTLS) and TLS material are read from the topology this
 * controller renders, so {@code MachineLauncher.up} boots each cluster with its own posture
 * correctly. The control-plane calls this class itself makes ({@link ControlPlaneApi}, for artifact
 * pushes and the bundle deploy) instead follow {@code IvaldiMain}'s own process-wide {@code
 * gimle.transport.protocol}/{@code gimle.tls.*} configuration, the same way every other Gimlé
 * tool's outbound calls do -- a cluster connection's own {@code clientCertPath}/{@code
 * clientKeyPath} are stored and returned by the {@code /api/clusters} surface for the console's own
 * use, but do not yet override this controller's outbound TLS identity per run. Running Ivaldi
 * itself with the matching {@code -Dgimle.transport.protocol}/{@code -Dgimle.tls.*} flags for the
 * one cluster transport in play covers today's local, single-cluster-at-a-time use.
 *
 * <p>{@code POST /api/runs/current/dry-run} (the tier-3 {@code ?dryRun=true} proxy) is not
 * implemented yet -- a separate addition once this run engine itself is exercised end to end.
 */
public final class RunController {

  private static final Logger log = LoggerFactory.getLogger(RunController.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ClusterStore clusters;
  private final Path workspaceRoot;
  private volatile ActiveRun current;

  public RunController(ClusterStore clusters, Path dataRoot) {
    this.clusters = clusters;
    this.workspaceRoot = dataRoot.resolve("runs");
    try {
      Files.createDirectories(workspaceRoot);
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("failed creating run workspace root: " + workspaceRoot, e);
    }
  }

  /** Thrown for a 409: a run is already in flight. */
  public static final class RunInProgressException extends RuntimeException {
    public RunInProgressException(String runId) {
      super("a run is already in progress: " + runId);
    }
  }

  /** Thrown for a 404: no such cluster, or no run has ever started. */
  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /** {@code {lines, nextCursor}}, the public shape of a log page -- see {@link #log}. */
  public record LogPage(List<String> lines, int nextCursor) {}

  public synchronized Map<String, Object> start(
      String clusterId,
      Optional<String> blueprintId,
      List<RenderedFile> files,
      Map<String, String> values) {
    if (current != null && current.status.isInFlight()) {
      throw new RunInProgressException(current.id);
    }
    if (clusters.get(clusterId).isEmpty()) {
      throw new NotFoundException("no such cluster: " + clusterId);
    }
    ActiveRun run = new ActiveRun(mintRunId(), clusterId, blueprintId);
    current = run;
    Thread.ofVirtual().start(() -> execute(run, files, values));
    return snapshotOf(run).toJsonMap();
  }

  public Map<String, Object> currentSnapshotJson() {
    return (current == null ? RunSnapshot.idle() : snapshotOf(current)).toJsonMap();
  }

  /** Empty when no run with this id exists at all -- distinct from an empty log page. */
  public Optional<LogPage> log(String runId, int cursor) {
    if (current == null || !current.id.equals(runId)) {
      return Optional.empty();
    }
    RunLog.Page page = current.log.since(cursor);
    return Optional.of(new LogPage(page.lines(), page.nextCursor()));
  }

  public synchronized Map<String, Object> stop() {
    if (current == null) {
      throw new NotFoundException("no run to stop");
    }
    ActiveRun run = current;
    if (run.status.isInFlight()) {
      throw new RunInProgressException(run.id);
    }
    run.status = RunStatus.STOPPING;
    run.updatedAt = Instant.now();
    Thread.ofVirtual().start(() -> teardown(run));
    return snapshotOf(run).toJsonMap();
  }

  // ---- the pipeline ----

  private void execute(ActiveRun run, List<RenderedFile> files, Map<String, String> values) {
    try {
      run.status = RunStatus.VALIDATING;
      List<Finding> findings = FileSetValidator.validate(files);
      List<Finding> errors =
          findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR).toList();
      if (!errors.isEmpty()) {
        throw new RunFailedException(
            "validation failed: "
                + errors.stream().map(Finding::message).collect(Collectors.joining("; ")));
      }
      run.log.append("validated " + files.size() + " file(s), 0 errors");

      RenderedFile topologyFile = requireFile(files, "topology.yaml");
      RenderedFile bundleFile = requireFile(files, "bundle.yaml");
      Topology topology = TopologyParser.parse(streamOf(topologyFile));
      String machine = topology.machines().get(0).name();
      ResolvedRuntime runtime = resolveRuntime(topology);

      Optional<String> appliedTopology = clusters.appliedTopology(run.clusterId);
      boolean reboot =
          appliedTopology.isEmpty()
              || !normalizeTopology(appliedTopology.get())
                  .equals(normalizeTopology(topologyFile.content()));

      run.status = RunStatus.BOOTING;
      if (reboot) {
        run.log.append(
            appliedTopology.isEmpty()
                ? "no topology previously applied to this cluster -- booting fresh"
                : "topology changed since this cluster's last run -- rebooting");
        List<String> conflicts = PortPreflight.conflictsOn(topology, machine);
        if (!conflicts.isEmpty()) {
          throw new RunFailedException("port(s) already in use: " + String.join(", ", conflicts));
        }
        if (appliedTopology.isPresent()) {
          downQuietly(run, appliedTopology.get());
          // Cleared the instant nothing is known to be running any more, not after the up below
          // succeeds: if up throws, a stale "applied" text would otherwise make the *next* run
          // wrongly think a deploy-only apply is safe against a cluster that isn't actually up.
          clusters.clearAppliedTopology(run.clusterId);
        }
        if (topology.transport() == Transport.MTLS) {
          ensurePkiMaterial(topology, runtime, run);
        }
        List<RunRecord> records;
        try {
          records = MachineLauncher.up(topology, machine, runtime, printStreamTo(run));
        } catch (RuntimeException upFailed) {
          // A partial boot can still hold ports and pids under this dataRoot; best-effort clean
          // it up so the *next* attempt's own up() doesn't fail on a conflict this one left
          // behind. appliedTopology is already cleared above, so nothing else believes this
          // cluster is running.
          run.log.append(
              "up failed, tearing down any partially-started processes: " + upFailed.getMessage());
          downQuietly(run, topologyFile.content());
          throw upFailed;
        }
        run.processes =
            records.stream()
                .map(r -> new RunSnapshot.ProcessInfo(r.role(), r.readinessAddress(), true))
                .toList();
        run.rebooted = true;
        run.log.append("booted " + records.size() + " process(es) on machine " + machine);
        clusters.recordAppliedTopology(run.clusterId, topologyFile.content());
      } else {
        run.log.append("topology unchanged -- deploying onto the running cluster without a reboot");
      }

      Map<String, Object> cluster =
          Json.asObject(Json.parse(clusters.get(run.clusterId).orElseThrow()));
      String controlPlaneUrl = String.valueOf(cluster.get("controlPlaneUrl"));
      String serverAddress = stripScheme(controlPlaneUrl);

      run.status = RunStatus.SEEDING;
      Path workspace = workspaceRoot.resolve(run.id);
      writeWorkspace(workspace, files);
      List<RenderedFile> jarWorkloads = jarSourcedWorkloads(files);
      ControlPlaneApi api = new ControlPlaneApi(serverAddress);
      for (RenderedFile manifest : jarWorkloads) {
        pushArtifact(api, manifest, run);
      }
      if (jarWorkloads.isEmpty()) {
        run.log.append("no jar-sourced workloads to push");
      }

      run.status = RunStatus.DEPLOYING;
      Bundle bundle = BundleParser.parse(streamOf(bundleFile));
      List<String> setFlags =
          values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
      Map<String, String> merged =
          ValueOverrides.merge(bundle.values(), Optional.empty(), setFlags);
      RenderedBundle rendered = BundleRenderer.render(bundle, merged, workspace);

      Optional<ReleaseMeta> meta = ReleaseLedger.readMeta(api, rendered.name());
      int revision;
      if (meta.isEmpty()) {
        ReleaseReconciler.DeployOutcome outcome =
            ReleaseReconciler.deployFresh(api, rendered, true, printStreamTo(run));
        revision = outcome.revision();
        run.log.append(
            "release " + rendered.name() + " deployed fresh (revision " + revision + ")");
      } else {
        var previous =
            ReleaseLedger.readRevision(api, rendered.name(), meta.get().currentRevision())
                .orElseThrow(
                    () ->
                        new RunFailedException(
                            "release '"
                                + rendered.name()
                                + "' has no revision "
                                + meta.get().currentRevision()
                                + " recorded in its ledger"));
        List<ResourceRef> toPrune = ReleaseReconciler.computePrune(rendered, previous);
        ReleaseReconciler.UpgradeOutcome outcome =
            ReleaseReconciler.upgradeExisting(
                api, rendered, meta.get(), toPrune, true, printStreamTo(run));
        revision = outcome.revision();
        run.log.append(
            "release "
                + rendered.name()
                + " upgraded (revision "
                + revision
                + ", "
                + toPrune.size()
                + " pruned)");
      }
      run.revision = Optional.of(revision);

      run.status = RunStatus.RUNNING;
      run.log.append("run complete");
    } catch (RunFailedException e) {
      fail(run, e.getMessage());
    } catch (RuntimeException e) {
      log.warn("run {} failed", run.id, e);
      fail(run, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    } finally {
      run.updatedAt = Instant.now();
    }
  }

  private void teardown(ActiveRun run) {
    try {
      Optional<String> appliedTopology = clusters.appliedTopology(run.clusterId);
      if (appliedTopology.isEmpty()) {
        run.log.append("nothing recorded as applied to this cluster -- nothing to stop");
      } else {
        downQuietly(run, appliedTopology.get());
        clusters.clearAppliedTopology(run.clusterId);
        run.log.append("cluster stopped");
      }
      run.status = RunStatus.IDLE;
      run.processes = List.of();
    } catch (RuntimeException e) {
      log.warn("stopping run {} failed", run.id, e);
      fail(run, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    } finally {
      run.updatedAt = Instant.now();
    }
  }

  private void downQuietly(ActiveRun run, String topologyYaml) {
    try {
      Topology topology =
          TopologyParser.parse(
              new ByteArrayInputStream(topologyYaml.getBytes(StandardCharsets.UTF_8)));
      ResolvedRuntime runtime = resolveRuntime(topology);
      run.log.append("stopping the previous process tree under " + runtime.dataRoot());
      MachineLauncher.down(runtime.dataRoot(), printStreamTo(run));
    } catch (RuntimeException e) {
      // Best-effort: a process tree that is already gone (or was never fully up) must not block a
      // reboot or a stop -- MachineLauncher.down itself already tolerates a missing ledger, so
      // this only guards against a truly unreadable prior topology.
      run.log.append(
          "stopping the previous process tree failed, continuing anyway: " + e.getMessage());
    }
  }

  private void ensurePkiMaterial(Topology topology, ResolvedRuntime runtime, ActiveRun run) {
    Path materialDir = topology.tls().orElseThrow().materialDir();
    if (Files.exists(materialDir.resolve("ca.crt"))) {
      run.log.append("reusing existing TLS material under " + materialDir);
      return;
    }
    run.log.append("minting TLS material under " + materialDir);
    PkiInit.run(topology, runtime, printStreamTo(run));
  }

  private void pushArtifact(ControlPlaneApi api, RenderedFile manifest, ActiveRun run) {
    Map<?, ?> mapping = readMapping(manifest.content());
    Object artifactPath = mapping.get("artifactPath");
    if (!(artifactPath instanceof String pathString) || pathString.isBlank()) {
      return;
    }
    Path jar = Path.of(pathString);
    ModuleArtifact artifact;
    try {
      artifact = ModuleArtifactReader.read(jar);
    } catch (RuntimeException e) {
      throw new RunFailedException(
          "not a pushable module artifact at "
              + jar
              + " (from "
              + manifest.path()
              + "): "
              + e.getMessage());
    }
    String moduleId = artifact.id().name();
    String version = artifact.id().version().toString();
    api.putFile("/artifacts/" + moduleId + "/" + version, jar);
    run.log.append("pushed artifact " + moduleId + "@" + version + " from " + jar);
  }

  private static List<RenderedFile> jarSourcedWorkloads(List<RenderedFile> files) {
    List<RenderedFile> jars = new ArrayList<>();
    for (RenderedFile file : files) {
      if (!file.path().startsWith("manifests/") || !file.path().endsWith(".yaml")) {
        continue;
      }
      Map<?, ?> mapping = readMapping(file.content());
      if (mapping.containsKey("artifactPath")) {
        jars.add(file);
      }
    }
    return jars;
  }

  private static void writeWorkspace(Path workspace, List<RenderedFile> files) {
    try {
      Files.createDirectories(workspace);
      for (RenderedFile file : files) {
        Path target = workspace.resolve(file.path());
        // Every RenderedFile#path is a relative file path under the workspace root (see its own
        // javadoc), so resolving it against workspace always yields a target with a parent --
        // this is never actually null, but java.nio.file.Path#getParent is typed to allow it.
        Files.createDirectories(
            java.util.Objects.requireNonNull(target.getParent(), () -> "no parent for " + target));
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
      }
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("failed writing run workspace under " + workspace, e);
    }
  }

  private static void fail(ActiveRun run, String message) {
    run.status = RunStatus.FAILED;
    run.error = Optional.ofNullable(message);
    run.log.append("FAILED: " + message);
  }

  private static RunSnapshot snapshotOf(ActiveRun run) {
    return new RunSnapshot(
        run.id,
        run.clusterId,
        run.blueprintId,
        run.status,
        run.rebooted,
        run.processes,
        run.revision,
        run.error,
        run.startedAt.toString(),
        run.updatedAt.toString());
  }

  private static String mintRunId() {
    return "run-"
        + Instant.now().toEpochMilli()
        + "-"
        + Integer.toHexString(RANDOM.nextInt(0xFFFF));
  }

  private static RenderedFile requireFile(List<RenderedFile> files, String path) {
    return files.stream()
        .filter(f -> f.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new RunFailedException("no " + path + " in the submitted file set"));
  }

  /** Ignores blank lines and trailing whitespace so a run doesn't reboot over formatting alone. */
  private static String normalizeTopology(String text) {
    return text.lines()
        .map(String::stripTrailing)
        .filter(l -> !l.isBlank())
        .collect(Collectors.joining("\n"));
  }

  private static ResolvedRuntime resolveRuntime(Topology topology) {
    return ResolvedRuntime.resolve(
        topology.runtime(), "java", System.getProperty("java.class.path"), Path.of("gimle-data"));
  }

  private static String stripScheme(String url) {
    return url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
  }

  private static java.io.InputStream streamOf(RenderedFile file) {
    return new ByteArrayInputStream(file.content().getBytes(StandardCharsets.UTF_8));
  }

  private static Map<?, ?> readMapping(String content) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw = yaml.load(content);
    return raw instanceof Map<?, ?> map ? map : Map.of();
  }

  /** Relays a run's own log into {@link ActiveRun#log} as if it were a Hilmir CLI's stdout. */
  private static PrintStream printStreamTo(ActiveRun run) {
    return new PrintStream(
        new java.io.OutputStream() {
          private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

          @Override
          public void write(int b) {
            if (b == '\n') {
              run.log.append(buffer.toString(StandardCharsets.UTF_8));
              buffer.reset();
            } else {
              buffer.write(b);
            }
          }
        },
        true,
        StandardCharsets.UTF_8);
  }

  /** Internal control-flow exception: a step failed with a message worth showing verbatim. */
  private static final class RunFailedException extends RuntimeException {
    RunFailedException(String message) {
      super(message);
    }
  }

  /** Mutable state for one run, owned entirely by {@link RunController}. */
  private static final class ActiveRun {
    final String id;
    final String clusterId;
    final Optional<String> blueprintId;
    final RunLog log = new RunLog();
    final Instant startedAt = Instant.now();
    volatile RunStatus status = RunStatus.VALIDATING;
    volatile boolean rebooted;
    volatile List<RunSnapshot.ProcessInfo> processes = List.of();
    volatile Optional<Integer> revision = Optional.empty();
    volatile Optional<String> error = Optional.empty();
    volatile Instant updatedAt = Instant.now();

    ActiveRun(String id, String clusterId, Optional<String> blueprintId) {
      this.id = id;
      this.clusterId = clusterId;
      this.blueprintId = blueprintId;
    }
  }
}
