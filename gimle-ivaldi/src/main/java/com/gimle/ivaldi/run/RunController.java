package com.gimle.ivaldi.run;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.hilmir.launch.MachineLauncher;
import com.gimle.hilmir.launch.PkiInit;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.release.Bundle;
import com.gimle.hilmir.release.BundleParser;
import com.gimle.hilmir.release.BundleRenderer;
import com.gimle.hilmir.release.ControlPlaneApi;
import com.gimle.hilmir.release.KeyRef;
import com.gimle.hilmir.release.ReleaseLedger;
import com.gimle.hilmir.release.ReleaseMeta;
import com.gimle.hilmir.release.ReleaseReconciler;
import com.gimle.hilmir.release.ReleaseRevision;
import com.gimle.hilmir.release.RenderedBundle;
import com.gimle.hilmir.release.RenderedSecretEntry;
import com.gimle.hilmir.release.ResourceRef;
import com.gimle.hilmir.release.ValueOverrides;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.topology.Transport;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.validate.FileSetValidator;
import com.gimle.ivaldi.validate.Finding;
import com.gimle.ivaldi.validate.JarArtifact;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
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
 * deploy, {@link ControlPlaneApi#putFile} for jar-sourced artifact pushes, {@link
 * ControlPlaneApi#putJson}/{@link ControlPlaneApi#postJson} for the resources that aren't Bundle
 * workloads (see {@link #standaloneManifests}). One run at a time per deployment: a second {@link
 * #start} for the same (cluster, blueprint) pair while one is {@linkplain RunStatus#isInFlight() in
 * flight} is refused -- see "One cluster, many deployments" below for what a cluster itself may
 * hold at once.
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
 * <h2>One cluster, many deployments</h2>
 *
 * <p>A cluster's infra is not owned by any one blueprint. Once its topology is up, any number of
 * blueprints may each deploy their own bundle onto it -- each tracked as its own run, keyed by
 * (cluster, blueprint) rather than by cluster alone -- as long as every one of them renders the
 * exact same topology (the deploy-only path above): a topology *change* would tear the shared
 * process tree down and rebuild it, taking every other deployment on it down too, so that is
 * refused outright while any other deployment on the cluster is still live (see {@link
 * #conflictingRebootMessage}). Stopping one deployment on a shared cluster only undeploys its own
 * release; the infra itself is torn down only when the deployment being stopped is the last live
 * one on that cluster (see {@link #teardown}).
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

  /** The manifest kinds a run applies itself rather than handing to the bundle deploy. */
  private static final Set<String> STANDALONE_KINDS =
      Set.of("Service", "NetworkPolicy", "LimitRange");

  /** How long a readiness probe's answer is reused before the next snapshot re-asks. */
  private static final Duration READINESS_CACHE = Duration.ofSeconds(2);

  /** How long a shutdown waits for a run's own worker to unwind before tearing down under it. */
  private static final Duration SHUTDOWN_WORKER_GRACE = Duration.ofSeconds(10);

  private final ClusterStore clusters;
  private final Path workspaceRoot;

  /**
   * Every run this process is holding, keyed by the (cluster, blueprint) pair it targets -- see
   * {@link #deploymentKey}.
   *
   * <p>Keyed by deployment rather than by cluster alone because a cluster's infra can host more
   * than one blueprint's own deployment at once (see the class javadoc's "One cluster, many
   * deployments" section): two deployments against the same cluster still fight over the same
   * process tree if their topologies ever disagree, but otherwise run independently, each with its
   * own status, log and stop. A run addressed with no blueprint id (a direct, low-level API call
   * rather than the console's own) falls back to a single anonymous slot per cluster, exactly the
   * one run a cluster could ever hold before deployments existed.
   */
  private final Map<String, ActiveRun> runsByDeployment = new ConcurrentHashMap<>();

  public RunController(ClusterStore clusters, Path dataRoot) {
    this.clusters = clusters;
    this.workspaceRoot = dataRoot.resolve("runs");
    try {
      Files.createDirectories(workspaceRoot);
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("failed creating run workspace root: " + workspaceRoot, e);
    }
    adoptRunningCluster();
  }

  /**
   * Re-adopts a cluster this Ivaldi launched before it was itself restarted. The run state lives in
   * memory, so without this a restart left a live process tree that nothing could see or stop: the
   * API reported idle and refused the teardown while four JVMs held their ports. Everything needed
   * is already on disk -- which topology was last applied to each cluster, which blueprints had a
   * deployment recorded against it, and the process ledger that topology's own data root holds --
   * so the tree is picked back up rather than abandoned. One {@link ActiveRun} is rebuilt per
   * recorded blueprint (or a single anonymous one, for a cluster whose only deployment never named
   * a blueprint), all sharing the same recovered process list, so each blueprint's own Runner page
   * still finds its own run by id after the restart. Best-effort by construction: an unreadable
   * topology or a ledger whose processes are all gone simply means there is nothing to adopt.
   */
  private void adoptRunningCluster() {
    for (Map<String, Object> cluster : clusters.list()) {
      Object id = cluster.get("id");
      if (!(id instanceof String clusterId)) {
        continue;
      }
      Optional<String> applied = clusters.appliedTopology(clusterId);
      if (applied.isEmpty()) {
        continue;
      }
      // Recorded beside the applied topology when each run started, so the recovered cluster's
      // deployments are still attributable to the blueprints that built them -- adopting it with
      // none left it running and belonging to nothing, which no screen could then show.
      Set<String> deploymentBlueprintIds = clusters.deployments(clusterId);
      try {
        Topology topology =
            TopologyParser.parse(
                new ByteArrayInputStream(applied.get().getBytes(StandardCharsets.UTF_8)));
        List<RunRecord> records =
            MachineLauncher.recordedProcesses(resolveRuntime(topology).dataRoot());
        List<RunRecord> alive =
            records.stream()
                .filter(r -> ProcessHandle.of(r.pid()).map(ProcessHandle::isAlive).orElse(false))
                .toList();
        if (alive.isEmpty()) {
          clusters.clearAppliedTopology(clusterId);
          continue;
        }
        List<RunSnapshot.ProcessInfo> processInfos = processInfos(alive, topology);
        List<Optional<String>> deploymentOwners =
            deploymentBlueprintIds.isEmpty()
                ? List.of(Optional.empty())
                : deploymentBlueprintIds.stream().map(Optional::of).toList();
        for (Optional<String> owner : deploymentOwners) {
          ActiveRun adopted = new ActiveRun(mintRunId(), clusterId, owner);
          adopted.status = RunStatus.RUNNING;
          adopted.processes = processInfos;
          adopted.log.append(
              "adopted "
                  + alive.size()
                  + " process(es) still running for cluster "
                  + clusterId
                  + owner.map(o -> " (deployment " + o + ")").orElse("")
                  + " from a previous Ivaldi process");
          runsByDeployment.put(deploymentKey(clusterId, owner), adopted);
        }
      } catch (RuntimeException e) {
        log.warn("could not adopt a running cluster for {}: {}", clusterId, e.getMessage());
      }
    }
  }

  /**
   * The key a run against {@code clusterId} for {@code blueprintId} is tracked under -- see {@link
   * #runsByDeployment}. A blueprint id is what disambiguates two deployments sharing one cluster's
   * infra; a request naming none falls back to a single per-cluster slot, matching the one run a
   * cluster could ever hold before deployments existed.
   */
  private static String deploymentKey(String clusterId, Optional<String> blueprintId) {
    return clusterId + "::" + blueprintId.orElse("");
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

  /**
   * Thrown for a 409: deleting a cluster connection, or a blueprint, out from under a still-tracked
   * deployment (live or failed-but-not-torn-down) used to succeed silently, leaving the real
   * process tree running with no cluster/blueprint record and no run pointing at it any more.
   */
  public static final class DeploymentInUseException extends RuntimeException {
    public DeploymentInUseException(String message) {
      super(message);
    }
  }

  /**
   * Refuses to proceed while {@code clusterId} still has any non-idle deployment -- see {@link
   * DeploymentInUseException}. {@code idle} is the only status safe to build over: every other
   * status either has a live process tree or (for a run this controller itself failed
   * mid-transition) may still have one, and this process is the only thing that remembers where.
   * Checked across every deployment on the cluster, not just one: a cluster shared by several
   * blueprints must not be deleted while any of them is still live.
   */
  public synchronized void requireNoLiveRun(String clusterId) {
    List<String> live =
        runsByDeployment.values().stream()
            .filter(run -> run.clusterId.equals(clusterId) && run.status != RunStatus.IDLE)
            .map(run -> run.id)
            .toList();
    if (!live.isEmpty()) {
      throw new DeploymentInUseException(
          "cluster "
              + clusterId
              + " has "
              + live.size()
              + " run(s) this process is tracking ("
              + String.join(", ", live)
              + ") -- stop them before deleting the cluster");
    }
  }

  /**
   * The blueprint-deletion counterpart to {@link #requireNoLiveRun(String)} -- deleting a blueprint
   * document is a different operation from deleting the cluster connection it was run against, so
   * it needs its own guard rather than relying on the cluster-side one, which a blueprint delete
   * never goes anywhere near. Without this, deleting a blueprint mid-run 404s the blueprint while
   * its real process tree keeps running, orphaned: no longer reachable from the blueprint list, and
   * tracked only under an id nothing else references any more.
   */
  public synchronized void requireNoLiveRunForBlueprint(String blueprintId) {
    List<String> live =
        runsByDeployment.values().stream()
            .filter(
                run ->
                    run.blueprintId.map(blueprintId::equals).orElse(false)
                        && run.status != RunStatus.IDLE)
            .map(run -> run.id)
            .toList();
    if (!live.isEmpty()) {
      throw new DeploymentInUseException(
          "blueprint "
              + blueprintId
              + " has "
              + live.size()
              + " run(s) this process is tracking ("
              + String.join(", ", live)
              + ") -- stop them before deleting the blueprint");
    }
  }

  /** {@code {lines, nextCursor}}, the public shape of a log page -- see {@link #log}. */
  public record LogPage(List<String> lines, int nextCursor) {}

  public synchronized Map<String, Object> start(
      String clusterId,
      Optional<String> blueprintId,
      List<RenderedFile> files,
      Map<String, String> values) {
    String key = deploymentKey(clusterId, blueprintId);
    ActiveRun existing = runsByDeployment.get(key);
    // Scoped to this exact deployment: a run elsewhere -- another blueprint's own deployment on
    // the same cluster included -- is none of this one's business. Two different blueprints
    // targeting the same cluster no longer collide here at all: each gets its own key, so a
    // second blueprint deploying onto a cluster the first still owns a live deployment on simply
    // starts its own, independently-tracked run (see the class javadoc's "One cluster, many
    // deployments" section) rather than being refused or silently taking over anything. What
    // still can collide is a *topology change* against a cluster another deployment shares --
    // refused later, once the rendered topology is known (see #conflictingRebootMessage).
    if (existing != null && existing.status.isInFlight()) {
      throw new RunInProgressException(existing.id);
    }
    if (clusters.get(clusterId).isEmpty()) {
      throw new NotFoundException("no such cluster: " + clusterId);
    }
    ActiveRun run = new ActiveRun(mintRunId(), clusterId, blueprintId);
    runsByDeployment.put(key, run);
    blueprintId.ifPresent(id -> clusters.recordDeployment(clusterId, id));
    run.worker = Thread.ofVirtual().start(() -> execute(run, files, values));
    return snapshotOf(run).toJsonMap();
  }

  /** Every run this process holds, newest first -- what the blueprint list and Clusters read. */
  public List<Map<String, Object>> allSnapshotsJson() {
    return runsByDeployment.values().stream()
        .sorted(Comparator.comparing((ActiveRun r) -> r.startedAt).reversed())
        .map(run -> snapshotOf(run).toJsonMap())
        .toList();
  }

  /**
   * The most recently started deployment against {@code clusterId}, or an idle snapshot when this
   * process holds none. Ambiguous once a cluster hosts more than one deployment -- kept for direct,
   * low-level callers that address a cluster with no blueprint of their own; every other caller
   * should ask by blueprint instead (see {@link #blueprintSnapshotJson}), which stays unambiguous
   * no matter how many deployments share the cluster.
   */
  public Map<String, Object> clusterSnapshotJson(String clusterId) {
    return runsByDeployment.values().stream()
        .filter(run -> run.clusterId.equals(clusterId))
        .max(Comparator.comparing(run -> run.startedAt))
        .map(run -> snapshotOf(run).toJsonMap())
        .orElseGet(() -> RunSnapshot.idle().toJsonMap());
  }

  /**
   * The run a given blueprint owns, or an idle snapshot. What a Runner screen asks for, so that a
   * page never renders a run belonging to another blueprint -- and, unlike {@link
   * #clusterSnapshotJson}, stays unambiguous no matter how many other blueprints share the same
   * cluster.
   */
  public Map<String, Object> blueprintSnapshotJson(String blueprintId) {
    return runsByDeployment.values().stream()
        .filter(run -> run.blueprintId.map(blueprintId::equals).orElse(false))
        .max(Comparator.comparing(run -> run.startedAt))
        .map(run -> snapshotOf(run).toJsonMap())
        .orElseGet(() -> RunSnapshot.idle().toJsonMap());
  }

  /**
   * The most recently started run, kept for the single-run clients that predate the registry.
   * Ambiguous by construction once more than one deployment is running -- whether on different
   * clusters or sharing one -- which is why every screen should ask by blueprint or by cluster
   * instead.
   */
  public Map<String, Object> currentSnapshotJson() {
    return runsByDeployment.values().stream()
        .max(Comparator.comparing(run -> run.startedAt))
        .map(run -> snapshotOf(run).toJsonMap())
        .orElseGet(() -> RunSnapshot.idle().toJsonMap());
  }

  /** Empty when no run with this id exists at all -- distinct from an empty log page. */
  public Optional<LogPage> log(String runId, int cursor) {
    return byId(runId)
        .map(
            run -> {
              RunLog.Page page = run.log.since(cursor);
              return new LogPage(page.lines(), page.nextCursor());
            });
  }

  private Optional<ActiveRun> byId(String runId) {
    return runsByDeployment.values().stream().filter(run -> run.id.equals(runId)).findFirst();
  }

  /**
   * Stops whatever this controller is holding. A run still in flight is cancelled rather than
   * refused: a boot waiting out a readiness timeout is exactly when an operator most wants out, and
   * refusing left them with no way to stop, no way to start another, and a growing process tree.
   * The worker is interrupted and finishes the teardown on its own way out, so the stop always runs
   * after the boot has actually stopped touching the cluster.
   */
  public synchronized Map<String, Object> stop() {
    ActiveRun latest =
        runsByDeployment.values().stream()
            .filter(run -> run.status != RunStatus.IDLE)
            .max(Comparator.comparing(run -> run.startedAt))
            .orElseThrow(() -> new NotFoundException("no run to stop"));
    return stopRun(latest);
  }

  /**
   * Stops the run belonging to one blueprint, wherever it is running -- the address a Runner screen
   * already has, and the only one that stays unambiguous once a cluster can host more than one
   * deployment.
   */
  public synchronized Map<String, Object> stopBlueprint(String blueprintId) {
    ActiveRun run =
        runsByDeployment.values().stream()
            .filter(r -> r.blueprintId.map(blueprintId::equals).orElse(false))
            .filter(r -> r.status != RunStatus.IDLE)
            .findFirst()
            .orElseThrow(
                () -> new NotFoundException("no run to stop for blueprint: " + blueprintId));
    return stopRun(run);
  }

  /**
   * Stops every deployment this process is tracking against one cluster -- a bulk teardown for a
   * direct, low-level caller addressing a cluster with no blueprint of their own, since once a
   * cluster can host several deployments "the run for this cluster" is no longer a single thing to
   * address by id alone the way {@link #stopBlueprint} still can.
   */
  public synchronized Map<String, Object> stopCluster(String clusterId) {
    List<ActiveRun> live =
        runsByDeployment.values().stream()
            .filter(run -> run.clusterId.equals(clusterId) && run.status != RunStatus.IDLE)
            .toList();
    if (live.isEmpty()) {
      throw new NotFoundException("no run to stop for cluster: " + clusterId);
    }
    Map<String, Object> last = null;
    for (ActiveRun run : live) {
      last = stopRun(run);
    }
    return last;
  }

  /**
   * Tears down every cluster this process launched. Called from the shutdown hook: a run's process
   * tree is a child of nothing -- it outlives Ivaldi by design -- so without this a Ctrl+C left
   * every cluster running, holding its ports, with no supervisor and nothing left that knew how to
   * stop it.
   */
  public void stopAll() {
    for (ActiveRun run : List.copyOf(runsByDeployment.values())) {
      if (run.status == RunStatus.IDLE) {
        continue;
      }
      try {
        Thread worker = run.worker;
        if (worker != null && worker.isAlive()) {
          run.cancelRequested = true;
          worker.interrupt();
          worker.join(SHUTDOWN_WORKER_GRACE.toMillis());
        }
        Thread.interrupted();
        teardown(run);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn("could not stop cluster {} on shutdown: {}", run.clusterId, e.getMessage());
      }
    }
  }

  private Map<String, Object> stopRun(ActiveRun run) {
    run.status = RunStatus.STOPPING;
    run.updatedAt = Instant.now();
    Thread worker = run.worker;
    if (worker != null && worker.isAlive()) {
      // Already cancelling: the worker is on its way out and finishes the teardown itself, so a
      // second press must not start a competing one.
      if (!run.cancelRequested) {
        run.cancelRequested = true;
        run.log.append("stop requested -- cancelling the run in flight");
        worker.interrupt();
      }
      return snapshotOf(run).toJsonMap();
    }
    // The worker is gone, whether or not this run was once cancelled. Returning early here on the
    // cancelled flag left the status stuck at STOPPING, which reads as in-flight, so every later
    // run was refused with a 409 until the process was restarted.
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
      requireJarArtifactsReadable(files);

      RenderedFile topologyFile = requireFile(files, "topology.yaml");
      RenderedFile bundleFile = requireFile(files, "bundle.yaml");
      Topology topology = TopologyParser.parse(streamOf(topologyFile));
      String machine = requireSingleMachine(topology);
      ResolvedRuntime runtime = resolveRuntime(topology);

      // Resolved before the boot below, not after it: a cluster connection with no usable control
      // plane URL can never finish a run, and finding that out only once four JVMs are already up
      // leaves the user with a "failed" run and a live process tree to clean up by hand.
      Map<String, Object> cluster =
          Json.asObject(Json.parse(clusters.get(run.clusterId).orElseThrow()));
      String serverAddress = serverAddressOf(cluster, run.clusterId);
      requireAddressUsableForTransport(serverAddress, topology, run.clusterId);

      Optional<String> appliedTopology = clusters.appliedTopology(run.clusterId);
      boolean reboot =
          appliedTopology.isEmpty()
              || !normalizeTopology(appliedTopology.get())
                  .equals(normalizeTopology(topologyFile.content()));

      // A topology *change* -- as opposed to this cluster's very first boot -- tears the whole
      // process tree down and rebuilds it, taking every other blueprint's own deployment on it
      // down too. Refused outright rather than silently done: see the class javadoc's "One
      // cluster, many deployments" section.
      if (reboot && appliedTopology.isPresent()) {
        Optional<String> conflict =
            conflictingRebootMessage(run.clusterId, otherLiveDeploymentBlueprintIds(run));
        if (conflict.isPresent()) {
          throw new RunFailedException(conflict.get());
        }
      }

      requireNotCancelled(run);
      run.status = RunStatus.BOOTING;
      if (reboot) {
        run.log.append(
            appliedTopology.isEmpty()
                ? "no topology previously applied to this cluster -- booting fresh"
                : "topology changed since this cluster's last run -- rebooting");
        // Stop this cluster's own previously-applied tree before the preflight, never after: the
        // preflight exists to catch a *foreign* process holding a declared port, and these
        // processes are precisely the ones this reboot replaces. Checking first made every
        // topology change collide with itself and fail the reboot it had just announced.
        if (appliedTopology.isPresent()) {
          downQuietly(run, appliedTopology.get());
          // Cleared the instant nothing is known to be running any more, not after the up below
          // succeeds: if up throws, a stale "applied" text would otherwise make the *next* run
          // wrongly think a deploy-only apply is safe against a cluster that isn't actually up.
          clusters.clearAppliedTopology(run.clusterId);
        }
        List<String> conflicts = PortPreflight.conflictsOn(topology, machine);
        if (!conflicts.isEmpty()) {
          throw new RunFailedException("port(s) already in use: " + String.join(", ", conflicts));
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
          // Cleared first: when the boot was stopped by a cancel, the interrupt is still set, and
          // a teardown running interrupted abandons every process after the first one it waits on.
          Thread.interrupted();
          downQuietly(run, topologyFile.content());
          throw upFailed;
        }
        run.processes = processInfos(records, topology);
        run.rebooted = true;
        run.log.append("booted " + records.size() + " process(es) on machine " + machine);
        clusters.recordAppliedTopology(run.clusterId, topologyFile.content());
        // clearAppliedTopology above (when this reboot replaced a previous one) wiped the whole
        // deployments set along with it -- correctly, since the reboot just took every previous
        // deployment's own infra down -- but that included this run's own membership, recorded
        // back in #start before the reboot decision was even made. Restored here so this run
        // still shows up as a deployment on the cluster it just (re)booted.
        run.blueprintId.ifPresent(id -> clusters.recordDeployment(run.clusterId, id));
      } else {
        run.log.append("topology unchanged -- deploying onto the running cluster without a reboot");
        // Reported the same way a fresh boot's processes are: this branch changes nothing about
        // the platform tree, but leaving it empty here answered every reader -- the run API, the
        // console's own readiness view -- as if a perfectly healthy, already-running cluster had
        // no processes at all, and the readiness re-probe this run object otherwise supports had
        // nothing to re-probe.
        run.processes =
            processInfos(MachineLauncher.recordedProcesses(runtime.dataRoot()), topology);
      }

      requireNotCancelled(run);
      run.status = RunStatus.SEEDING;
      Path workspace = workspaceRoot.resolve(run.id);
      writeWorkspace(workspace, files);
      List<JarArtifact> jarWorkloads = jarArtifacts(files);
      // Built here, after the boot above has minted whatever material this topology needs, and
      // from that topology rather than from this process's own configuration -- see
      // clientIdentityFor.
      ControlPlaneApi api =
          new ControlPlaneApi(serverAddress, clientIdentityFor(topology, cluster, run));
      for (JarArtifact jarArtifact : jarWorkloads) {
        pushArtifact(api, jarArtifact, run);
      }
      if (jarWorkloads.isEmpty()) {
        run.log.append("no jar-sourced workloads to push");
      }

      List<RenderedFile> standalone = standaloneManifests(files);
      for (RenderedFile manifest : standalone) {
        applyStandalone(api, manifest, run);
      }
      if (standalone.isEmpty()) {
        run.log.append("no standalone resources to apply");
      }

      requireNotCancelled(run);
      run.status = RunStatus.DEPLOYING;
      Bundle bundle = BundleParser.parse(streamOf(bundleFile));
      List<String> setFlags =
          values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
      Map<String, String> merged =
          ValueOverrides.merge(bundle.values(), Optional.empty(), setFlags);
      RenderedBundle declared = BundleRenderer.render(bundle, merged, workspace);
      RenderedBundle rendered = dropUnsuppliedSecrets(declared, run);
      // Recorded so a later stop of this exact run -- while another deployment still shares the
      // cluster's infra -- knows which release to undeploy rather than tearing the whole cluster
      // down (see #teardown).
      run.releaseName = Optional.of(rendered.name());

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
        // Against the declaring bundle, not the applied one: a secret withheld above because its
        // value was not re-entered is still declared, and pruning it would destroy exactly the
        // vault value that withholding it was meant to preserve.
        List<KeyRef> keysToPrune = ReleaseReconciler.computeKeyPrune(declared, previous);
        ReleaseReconciler.UpgradeOutcome outcome =
            ReleaseReconciler.upgradeExisting(
                api, rendered, meta.get(), toPrune, keysToPrune, true, printStreamTo(run));
        revision = outcome.revision();
        run.log.append(
            "release "
                + rendered.name()
                + " upgraded (revision "
                + revision
                + ", "
                + toPrune.size()
                + " resource(s) and "
                + keysToPrune.size()
                + " key(s) pruned)");
      }
      run.revision = Optional.of(revision);

      run.status = RunStatus.RUNNING;
      run.log.append("run complete");
    } catch (RunFailedException e) {
      if (cancelled(run)) {
        teardown(run);
      } else {
        fail(run, e.getMessage());
      }
    } catch (RuntimeException e) {
      // A cancelled run's own failure is the cancellation, whatever shape it arrived in -- an
      // interrupted sleep inside a readiness poll surfaces here as an ordinary runtime failure.
      if (cancelled(run)) {
        run.log.append("run cancelled");
        teardown(run);
      } else {
        log.warn("run {} failed", run.id, e);
        fail(run, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      }
    } finally {
      Thread.interrupted();
      run.updatedAt = Instant.now();
    }
  }

  /**
   * Whether this run was cancelled, clearing the interrupt on the way out.
   *
   * <p>The interrupt is what stopped the run, and it is still set at this point: leaving it set
   * meant the teardown that follows was itself interrupted, so the first {@code waitFor} threw and
   * every process after it was abandoned -- a cancel during a boot left five JVMs running while
   * reporting the cluster stopped.
   */
  private static boolean cancelled(ActiveRun run) {
    Thread.interrupted();
    return run.cancelRequested;
  }

  /**
   * Aborts at a phase boundary, so a cancelled run stops before starting its next piece of work.
   */
  private static void requireNotCancelled(ActiveRun run) {
    if (run.cancelRequested) {
      throw new RunFailedException("run cancelled");
    }
  }

  /**
   * Every other blueprint this process is still tracking a non-idle run for, against the same
   * cluster as {@code run} -- what a topology change must never silently tear down from under, and
   * what a stop must check before deciding whether it is safe to tear the shared infra down too.
   */
  private Set<String> otherLiveDeploymentBlueprintIds(ActiveRun run) {
    return runsByDeployment.values().stream()
        .filter(other -> other != run)
        .filter(other -> other.clusterId.equals(run.clusterId))
        .filter(other -> other.status != RunStatus.IDLE)
        .map(other -> other.blueprintId.orElse("(unnamed run)"))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * The refusal message for a topology change against a cluster {@code otherLiveBlueprintIds} still
   * share, or empty when the change is safe -- pulled out of {@link #execute} so this exact refusal
   * is directly testable without a live pipeline. A cluster can host many deployments once their
   * topologies agree (see the class javadoc's "Deploy-only vs. reboot" section), but a topology
   * *change* means tearing the whole process tree down and rebuilding it, which would take every
   * other deployment's own infra down with it too.
   */
  static Optional<String> conflictingRebootMessage(
      String clusterId, Set<String> otherLiveBlueprintIds) {
    if (otherLiveBlueprintIds.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "cluster "
            + clusterId
            + " is shared with other running deployment(s) ("
            + String.join(", ", otherLiveBlueprintIds)
            + ") whose infra this topology change would tear down -- stop them first, or make"
            + " this blueprint's topology match the cluster's current one to deploy onto it"
            + " without rebooting");
  }

  /**
   * Stops one run. The cluster's own process tree is only actually torn down when this is the last
   * live deployment on it -- while another blueprint's own deployment still shares the infra, this
   * only undeploys this run's own release (see {@link #undeployReleaseQuietly}), leaving the shared
   * process tree, and every other deployment on it, running.
   */
  private void teardown(ActiveRun run) {
    try {
      if (!otherLiveDeploymentBlueprintIds(run).isEmpty()) {
        undeployReleaseQuietly(run);
        run.blueprintId.ifPresent(id -> clusters.removeDeployment(run.clusterId, id));
        run.log.append("release undeployed -- cluster stays up for other deployments");
      } else {
        Optional<String> appliedTopology = clusters.appliedTopology(run.clusterId);
        if (appliedTopology.isEmpty()) {
          run.log.append("nothing recorded as applied to this cluster -- nothing to stop");
        } else {
          downQuietly(run, appliedTopology.get());
          clusters.clearAppliedTopology(run.clusterId);
          run.log.append("cluster stopped");
        }
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

  /**
   * Best-effort undeploy of this run's own release, leaving the cluster's shared infra untouched --
   * what {@link #teardown} falls back to instead of a full {@code MachineLauncher.down} while
   * another blueprint's own deployment still shares the cluster. A run adopted after a restart (see
   * {@link #adoptRunningCluster}) never learned its own release name, so it has nothing to undeploy
   * here beyond forgetting its own membership -- a documented, small gap rather than a crash.
   */
  private void undeployReleaseQuietly(ActiveRun run) {
    if (run.releaseName.isEmpty()) {
      run.log.append("no release recorded for this run -- nothing to undeploy");
      return;
    }
    String releaseName = run.releaseName.get();
    try {
      Map<String, Object> cluster =
          Json.asObject(Json.parse(clusters.get(run.clusterId).orElseThrow()));
      String serverAddress = serverAddressOf(cluster, run.clusterId);
      Optional<SSLContext> identity = Optional.empty();
      Optional<String> appliedTopology = clusters.appliedTopology(run.clusterId);
      if (appliedTopology.isPresent()) {
        Topology topology =
            TopologyParser.parse(
                new ByteArrayInputStream(appliedTopology.get().getBytes(StandardCharsets.UTF_8)));
        identity = clientIdentityFor(topology, cluster, run);
      }
      ControlPlaneApi api = new ControlPlaneApi(serverAddress, identity);
      Optional<ReleaseMeta> meta = ReleaseLedger.readMeta(api, releaseName);
      if (meta.isEmpty()) {
        run.log.append("release " + releaseName + " already gone -- nothing to undeploy");
        return;
      }
      Optional<ReleaseRevision> current =
          ReleaseLedger.readRevision(api, releaseName, meta.get().currentRevision());
      if (current.isEmpty()) {
        run.log.append(
            "release "
                + releaseName
                + " has no revision "
                + meta.get().currentRevision()
                + " recorded -- nothing to undeploy");
        return;
      }
      ReleaseReconciler.undeployRelease(api, releaseName, meta.get(), current.get(), false);
      run.log.append("undeployed release " + releaseName);
    } catch (RuntimeException e) {
      // Best-effort, the same posture downQuietly already takes for a full infra teardown: a
      // release that is already gone, or a control plane that has stopped answering, must not
      // block this run from settling to idle.
      run.log.append(
          "undeploying release " + releaseName + " failed, continuing anyway: " + e.getMessage());
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

  /**
   * Every jar a run will push, read before anything is torn down. The push itself is the first step
   * after the boot, so a mistyped path used to be discovered only once the running cluster had
   * already been stopped and respawned -- the whole cost of a reboot for a typo the validate phase
   * can see.
   */
  private static void requireJarArtifactsReadable(List<RenderedFile> files) {
    for (JarArtifact jarArtifact : jarArtifacts(files)) {
      readModuleArtifact(jarArtifact);
    }
  }

  private void pushArtifact(ControlPlaneApi api, JarArtifact jarArtifact, ActiveRun run) {
    ModuleArtifact artifact = readModuleArtifact(jarArtifact);
    String moduleId = artifact.id().name();
    String version = artifact.id().version().toString();
    api.putFile("/artifacts/" + moduleId + "/" + version, jarArtifact.jar());
    run.log.append("pushed artifact " + moduleId + "@" + version + " from " + jarArtifact.jar());
  }

  private static List<JarArtifact> jarArtifacts(List<RenderedFile> files) {
    try {
      return JarArtifact.readFrom(files);
    } catch (IllegalArgumentException malformed) {
      throw new RunFailedException(malformed.getMessage());
    }
  }

  private static ModuleArtifact readModuleArtifact(JarArtifact jarArtifact) {
    Path jar = jarArtifact.jar();
    if (!Files.isRegularFile(jar)) {
      throw new RunFailedException(
          "no jar at "
              + jar
              + " (for "
              + jarArtifact.manifestPath()
              + ") -- check the artifact path");
    }
    try {
      return ModuleArtifactReader.read(jar);
    } catch (RuntimeException notAModule) {
      throw new RunFailedException(
          "not a pushable module artifact at "
              + jar
              + " (for "
              + jarArtifact.manifestPath()
              + "): "
              + notAModule.getMessage());
    }
  }

  /**
   * The manifests that aren't Bundle workloads. {@code gimle-hilmir}'s own {@code BundleApplier}
   * maps a workload's {@code kind:} to a control-plane path prefix and knows only the five workload
   * kinds, so a Service, NetworkPolicy or LimitRange riding {@code bundle.workloads[]} would fail
   * the deploy outright. Each is a control-plane resource in its own right, applied here directly
   * -- the same calls {@code gimle apply -f} makes by hand -- before the bundle deploy, so a
   * workload that fronts one finds it already in place.
   */
  private static List<RenderedFile> standaloneManifests(List<RenderedFile> files) {
    List<RenderedFile> standalone = new ArrayList<>();
    for (RenderedFile file : files) {
      if (!file.path().startsWith("manifests/") || !file.path().endsWith(".yaml")) {
        continue;
      }
      if (STANDALONE_KINDS.contains(String.valueOf(readMapping(file.content()).get("kind")))) {
        standalone.add(file);
      }
    }
    return standalone;
  }

  private void applyStandalone(ControlPlaneApi api, RenderedFile manifest, ActiveRun run) {
    Map<?, ?> mapping = readMapping(manifest.content());
    switch (String.valueOf(mapping.get("kind"))) {
      case "LimitRange" -> applyLimitRange(api, manifest, mapping, run);
      case "Service" -> applyService(api, manifest, mapping, run);
      case "NetworkPolicy" -> applyNetworkPolicy(api, manifest, mapping, run);
      default ->
          throw new RunFailedException(
              "manifest " + manifest.path() + " has no standalone-resource handler");
    }
  }

  private void applyLimitRange(
      ControlPlaneApi api, RenderedFile manifest, Map<?, ?> mapping, ActiveRun run) {
    String tenantId = requireName(manifest, mapping, "LimitRange", "tenant 'name'");
    Map<String, Object> body = new LinkedHashMap<>();
    for (String key : List.of("minRequest", "maxRequest", "minLimit", "maxLimit")) {
      Object bound = mapping.get(key);
      if (bound != null) {
        body.put(key, bound);
      }
    }
    api.putJson("/limitranges/" + tenantId, Json.write(body));
    run.log.append("applied limitrange for tenant " + tenantId);
  }

  /**
   * {@code POST /services} creates or replaces by the name its own body carries, so re-running a
   * blueprint onto a live cluster re-applies a Service rather than colliding with the one already
   * there.
   */
  private void applyService(
      ControlPlaneApi api, RenderedFile manifest, Map<?, ?> mapping, ActiveRun run) {
    String name = requireName(manifest, mapping, "Service", "'name'");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    copyIfPresent(mapping, body, "tenantId");
    body.put("deploymentNames", stringList(mapping.get("deploymentNames")));
    Object port = mapping.get("port");
    if (port == null) {
      throw new RunFailedException("Service manifest " + manifest.path() + " has no 'port'");
    }
    body.put("port", port);
    copyIfPresent(mapping, body, "targetPort");
    List<String> advisories = api.postJson("/services", Json.write(body));
    run.log.append("applied service " + name);
    for (String advisory : advisories) {
      run.log.append("warning: service " + name + ": " + advisory);
    }
  }

  private void applyNetworkPolicy(
      ControlPlaneApi api, RenderedFile manifest, Map<?, ?> mapping, ActiveRun run) {
    Map<String, Object> body = networkPolicyBody(manifest, mapping);
    api.postJson("/networkpolicies", Json.write(body));
    run.log.append("applied networkpolicy " + body.get("name"));
  }

  /**
   * The {@code POST /networkpolicies} body for one rendered manifest -- pulled out of {@link
   * #applyNetworkPolicy} so this exact mapping is directly testable without a control plane.
   * Presence, not emptiness, is what the manifest actually means: the console renders
   * allowedCallerTenantIds/allowedCalleeTenantIds even as {@code []} to declare a real
   * deny-in-that-direction policy, and dropping a present-but-empty list here collapsed it into
   * "direction not restricted at all" -- the one shape the control plane refuses outright ("a
   * network policy must restrict at least one direction"). deploymentNames staying keyed on
   * presence too is what the manifest already omits when it means "the whole tenant" rather than
   * "none".
   */
  static Map<String, Object> networkPolicyBody(RenderedFile manifest, Map<?, ?> mapping) {
    String name = requireName(manifest, mapping, "NetworkPolicy", "'name'");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    Object tenantId = mapping.get("tenantId");
    if (!(tenantId instanceof String tenant) || tenant.isBlank()) {
      throw new RunFailedException(
          "NetworkPolicy manifest " + manifest.path() + " has no 'tenantId'");
    }
    body.put("tenantId", tenant);
    for (String key :
        List.of("deploymentNames", "allowedCallerTenantIds", "allowedCalleeTenantIds")) {
      if (mapping.containsKey(key)) {
        body.put(key, stringList(mapping.get(key)));
      }
    }
    return body;
  }

  private static String requireName(
      RenderedFile manifest, Map<?, ?> mapping, String kind, String field) {
    if (!(mapping.get("name") instanceof String name) || name.isBlank()) {
      throw new RunFailedException(kind + " manifest " + manifest.path() + " has no " + field);
    }
    return name;
  }

  private static void copyIfPresent(Map<?, ?> mapping, Map<String, Object> body, String key) {
    Object value = mapping.get(key);
    if (value != null) {
      body.put(key, value);
    }
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).distinct().toList();
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

  /**
   * Readiness is re-probed here rather than reported as the launcher saw it: a process killed from
   * outside Ivaldi -- or one that died on its own an hour into a run -- otherwise stayed "ready"
   * for as long as the run object lived, and the console offered live links to a dead port.
   *
   * <p>Cached briefly because a console polls this: without the window, one connect per process per
   * request turns a one-second poll into a steady trickle of sockets against every role.
   */
  private static List<RunSnapshot.ProcessInfo> refreshedProcesses(ActiveRun run) {
    if (run.processes.isEmpty()) {
      return run.processes;
    }
    Instant now = Instant.now();
    Instant checked = run.processesCheckedAt;
    if (checked != null && Duration.between(checked, now).compareTo(READINESS_CACHE) < 0) {
      return run.processes;
    }
    List<RunSnapshot.ProcessInfo> refreshed =
        run.processes.stream()
            .map(
                process ->
                    process.withReady(
                        MachineLauncher.isRunning(process.pid(), process.readinessAddress())))
            .toList();
    run.processes = refreshed;
    run.processesCheckedAt = now;
    return refreshed;
  }

  private static RunSnapshot snapshotOf(ActiveRun run) {
    return new RunSnapshot(
        run.id,
        run.clusterId,
        run.blueprintId,
        run.status,
        run.rebooted,
        refreshedProcesses(run),
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
  /**
   * The topology text reduced to what a reboot decision actually turns on. Blank lines and trailing
   * space are noise, and so is the topology's own {@code name:} -- it labels the design, names no
   * process, and binds no port, so renaming a blueprint used to stop and respawn every process for
   * a change nothing could observe.
   */
  private static String normalizeTopology(String text) {
    return text.lines()
        .map(String::stripTrailing)
        .filter(l -> !l.isBlank())
        .filter(l -> !l.startsWith("name:"))
        .collect(Collectors.joining("\n"));
  }

  /**
   * The client identity this run's own outbound calls use: empty for a plaintext topology, and the
   * operator material for an mTLS one.
   *
   * <p>Resolved per run rather than from this process's own {@code gimle.transport.protocol}/{@code
   * gimle.tls.*} configuration, for two reasons. One Ivaldi targets several clusters, which may not
   * share a transport, so its own system properties have no single correct answer. And an mTLS
   * cluster's material does not exist until the run that boots it mints it -- a process-wide
   * identity fixed at startup could therefore never be right for a cluster's first run, whereas by
   * the time this is called the boot phase has already put the files on disk.
   *
   * <p>Defaults to the {@code operator} leaf the topology's own material directory holds, which is
   * exactly what this run's PKI step writes there. A cluster connection carrying its own {@code
   * clientCertPath}/{@code clientKeyPath} overrides that -- for a cluster this Ivaldi did not boot,
   * or an operator identity kept elsewhere.
   */
  private Optional<SSLContext> clientIdentityFor(
      Topology topology, Map<String, Object> cluster, ActiveRun run) {
    Optional<TlsSettings> material = clientMaterialFor(topology, cluster);
    material.ifPresent(
        m -> run.log.append("authenticating to the control plane as " + m.certFile()));
    return material.map(SslContexts::forMutualTls);
  }

  /** The material {@link #clientIdentityFor} builds its context from; see that method. */
  static Optional<TlsSettings> clientMaterialFor(Topology topology, Map<String, Object> cluster) {
    if (topology.transport() != Transport.MTLS) {
      return Optional.empty();
    }
    Path materialDir = topology.tls().orElseThrow().materialDir();
    Path certFile =
        overridePath(cluster, "clientCertPath").orElse(materialDir.resolve("operator.crt"));
    Path keyFile =
        overridePath(cluster, "clientKeyPath").orElse(materialDir.resolve("operator.key"));
    Path caFile = overridePath(cluster, "caPath").orElse(materialDir.resolve("ca.crt"));
    for (Path file : List.of(certFile, keyFile, caFile)) {
      if (!Files.isRegularFile(file)) {
        throw new RunFailedException(
            "this cluster speaks mTLS but there is no client material at "
                + file
                + " -- a run that boots the cluster mints it, so this is a cluster booted"
                + " elsewhere: point the cluster connection at an operator certificate, or"
                + " re-run against a topology whose TLS material directory holds one");
      }
    }
    return Optional.of(new TlsSettings(certFile, keyFile, caFile));
  }

  private static Optional<Path> overridePath(Map<String, Object> cluster, String field) {
    Object raw = cluster.get(field);
    String value = raw == null ? "" : String.valueOf(raw).trim();
    return value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
  }

  /**
   * Refuses a control-plane address an mTLS cluster's own certificates can never match. Every leaf
   * is minted for its machine's declared hostname, so an IP literal fails subject-alternative-name
   * matching and a hostname the topology never declares has no leaf at all. Both surface as a TLS
   * handshake failure deep in the deploy step, which reads as a broken cluster rather than as a
   * wrong address.
   */
  private static void requireAddressUsableForTransport(
      String serverAddress, Topology topology, String clusterId) {
    if (topology.transport() != Transport.MTLS) {
      return;
    }
    String host = java.net.URI.create("http://" + serverAddress).getHost();
    if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.equals("[::1]")) {
      throw new RunFailedException(
          "cluster '"
              + clusterId
              + "' names the control plane by IP address ("
              + host
              + "), which no certificate in an mTLS cluster can match -- use the machine's"
              + " hostname instead");
    }
    List<String> hostnames = topology.machines().stream().map(m -> m.host()).toList();
    if (!hostnames.contains(host)) {
      throw new RunFailedException(
          "cluster '"
              + clusterId
              + "' names the control plane at host '"
              + host
              + "', which this topology never declares -- no certificate was minted for it."
              + " Declared host(s): "
              + String.join(", ", hostnames));
    }
  }

  /**
   * A run boots one machine, on this host. A multi-machine topology is a design to download and
   * hand to {@code hilmir up} once per machine, not something this process can bring up: booting
   * only the first left every workload on the others unplaced while the run reported success, and a
   * second machine's processes would in any case overwrite the first's run ledger, which is keyed
   * by data root alone.
   */
  private static String requireSingleMachine(Topology topology) {
    if (topology.machines().size() > 1) {
      throw new RunFailedException(
          "this blueprint declares "
              + topology.machines().size()
              + " machines ("
              + topology.machines().stream().map(m -> m.name()).collect(Collectors.joining(", "))
              + ") and a run boots one. Download the zip and run 'hilmir up' once per machine,"
              + " or design a single-machine cluster to run from here.");
    }
    return topology.machines().get(0).name();
  }

  /**
   * Drops any secret whose value resolved to empty, leaving whatever the vault already holds.
   *
   * <p>A secret's value is typed per run and deliberately never stored, so re-running after an
   * unrelated edit arrives with the field blank. Applying that blank overwrote a real credential
   * with an empty string at a new version, silently: the running instances kept the old value in
   * memory, so nothing looked wrong until the next instance start. An empty secret is never
   * something anyone means to store.
   */
  private static RenderedBundle dropUnsuppliedSecrets(RenderedBundle rendered, ActiveRun run) {
    List<RenderedSecretEntry> supplied =
        rendered.secrets().stream().filter(s -> !s.value().isEmpty()).toList();
    for (RenderedSecretEntry skipped : rendered.secrets()) {
      if (skipped.value().isEmpty()) {
        run.log.append(
            "no value supplied for secret "
                + skipped.tenant()
                + "/"
                + skipped.key()
                + " -- leaving whatever the vault already holds");
      }
    }
    if (supplied.size() == rendered.secrets().size()) {
      return rendered;
    }
    return new RenderedBundle(
        rendered.name(),
        rendered.version(),
        rendered.tenants(),
        rendered.config(),
        supplied,
        rendered.workloads());
  }

  private static ResolvedRuntime resolveRuntime(Topology topology) {
    return ResolvedRuntime.resolve(
        topology.runtime(), "java", System.getProperty("java.class.path"), Path.of("gimle-data"));
  }

  /**
   * The {@code host:port} a cluster connection's own {@code controlPlaneUrl} names, rejected up
   * front when it cannot be one. {@link ControlPlaneApi} builds its base URI as {@code scheme +
   * "://" + address}, so a blank or authority-less value surfaces as a raw {@code java.net.URI}
   * parser message ("Expected authority at index 7") from deep inside the deploy step, long after
   * the platform is up -- unreadable to anyone who did not write that parser, and far too late to
   * be useful. Checked here instead, before a single process is spawned.
   */
  private static String serverAddressOf(Map<String, Object> cluster, String clusterId) {
    Object raw = cluster.get("controlPlaneUrl");
    String url = raw == null ? "" : String.valueOf(raw).trim();
    String address = stripScheme(url);
    if (address.isBlank()) {
      throw new RunFailedException(
          "cluster '"
              + clusterId
              + "' has no control plane URL -- set one on the cluster connection, e.g."
              + " 127.0.0.1:8080");
    }
    try {
      if (java.net.URI.create("http://" + address).getHost() == null) {
        throw new IllegalArgumentException("no host");
      }
    } catch (RuntimeException notAnAddress) {
      throw new RunFailedException(
          "cluster '"
              + clusterId
              + "' has an unusable control plane URL ("
              + url
              + ") -- expected host:port, e.g. 127.0.0.1:8080");
    }
    return address;
  }

  /**
   * A {@link RunRecord} carries a blank readiness address for a process kind with no port-based
   * readiness signal, which today means the node agent alone: its gossip port is UDP, and the
   * launcher's own readiness poller only ever does a TCP connect, so it deliberately declares none.
   * Reporting that blank straight through left the console showing one role with no address beside
   * three that had one, reading as a missing field rather than as "this kind has none" -- so fall
   * back to the gossip address the topology itself declares for that agent.
   */
  private static List<RunSnapshot.ProcessInfo> processInfos(
      List<RunRecord> records, Topology topology) {
    List<RunSnapshot.ProcessInfo> infos = new ArrayList<>();
    for (RunRecord record : records) {
      String address = record.readinessAddress();
      if (address.isBlank()) {
        address = declaredAgentAddress(record.id(), topology).orElse("");
      }
      infos.add(
          new RunSnapshot.ProcessInfo(
              record.role(), address, record.pid(), record.readinessAddress(), true));
    }
    return List.copyOf(infos);
  }

  /** Matches on the {@code agent-<nodeId>} id the launch planner mints for every agent. */
  private static Optional<String> declaredAgentAddress(String recordId, Topology topology) {
    return topology.agents().stream()
        .filter(agent -> recordId.equals("agent-" + agent.nodeId()))
        .findFirst()
        .map(
            agent ->
                topology.machines().stream()
                        .filter(m -> m.name().equals(agent.machine()))
                        .findFirst()
                        .map(com.gimle.hilmir.topology.Machine::host)
                        .orElse("127.0.0.1")
                    + ":"
                    + agent.gossipPort());
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
    volatile Instant processesCheckedAt;
    volatile Optional<Integer> revision = Optional.empty();
    // Set once this run actually deploys a bundle -- see #undeployReleaseQuietly, which is the
    // only reader.
    volatile Optional<String> releaseName = Optional.empty();
    volatile Optional<String> error = Optional.empty();
    volatile Instant updatedAt = Instant.now();
    volatile boolean cancelRequested;
    volatile Thread worker;

    ActiveRun(String id, String clusterId, Optional<String> blueprintId) {
      this.id = id;
      this.clusterId = clusterId;
      this.blueprintId = blueprintId;
    }
  }
}
