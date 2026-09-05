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
import com.gimle.hilmir.release.ReleaseLedger;
import com.gimle.hilmir.release.ReleaseMeta;
import com.gimle.hilmir.release.ReleaseReconciler;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * workloads (see {@link #standaloneManifests}). One run at a time: a second {@link #start} while
 * one is {@linkplain RunStatus#isInFlight() in flight} is refused.
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

  /** The manifest kinds a run applies itself rather than handing to the bundle deploy. */
  private static final Set<String> STANDALONE_KINDS =
      Set.of("Service", "NetworkPolicy", "LimitRange");

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
    adoptRunningCluster();
  }

  /**
   * Re-adopts a cluster this Ivaldi launched before it was itself restarted. The run state lives in
   * memory, so without this a restart left a live process tree that nothing could see or stop: the
   * API reported idle and refused the teardown while four JVMs held their ports. Everything needed
   * is already on disk -- which topology was last applied to each cluster, and the process ledger
   * that topology's own data root holds -- so the tree is picked back up rather than abandoned.
   * Best-effort by construction: an unreadable topology or a ledger whose processes are all gone
   * simply means there is nothing to adopt.
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
        ActiveRun adopted = new ActiveRun(mintRunId(), clusterId, Optional.empty());
        adopted.status = RunStatus.RUNNING;
        adopted.processes = processInfos(alive, topology);
        adopted.log.append(
            "adopted "
                + alive.size()
                + " process(es) still running for cluster "
                + clusterId
                + " from a previous Ivaldi process");
        current = adopted;
        return;
      } catch (RuntimeException e) {
        log.warn("could not adopt a running cluster for {}: {}", clusterId, e.getMessage());
      }
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
    run.worker = Thread.ofVirtual().start(() -> execute(run, files, values));
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

  /**
   * Stops whatever this controller is holding. A run still in flight is cancelled rather than
   * refused: a boot waiting out a readiness timeout is exactly when an operator most wants out, and
   * refusing left them with no way to stop, no way to start another, and a growing process tree.
   * The worker is interrupted and finishes the teardown on its own way out, so the stop always runs
   * after the boot has actually stopped touching the cluster.
   */
  public synchronized Map<String, Object> stop() {
    if (current == null) {
      throw new NotFoundException("no run to stop");
    }
    ActiveRun run = current;
    run.status = RunStatus.STOPPING;
    run.updatedAt = Instant.now();
    if (run.cancelRequested) {
      return snapshotOf(run).toJsonMap();
    }
    Thread worker = run.worker;
    if (worker != null && worker.isAlive()) {
      run.cancelRequested = true;
      run.log.append("stop requested -- cancelling the run in flight");
      worker.interrupt();
      return snapshotOf(run).toJsonMap();
    }
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
          downQuietly(run, topologyFile.content());
          throw upFailed;
        }
        run.processes = processInfos(records, topology);
        run.rebooted = true;
        run.log.append("booted " + records.size() + " process(es) on machine " + machine);
        clusters.recordAppliedTopology(run.clusterId, topologyFile.content());
      } else {
        run.log.append("topology unchanged -- deploying onto the running cluster without a reboot");
      }

      requireNotCancelled(run);
      run.status = RunStatus.SEEDING;
      Path workspace = workspaceRoot.resolve(run.id);
      writeWorkspace(workspace, files);
      List<RenderedFile> jarWorkloads = jarSourcedWorkloads(files);
      // Built here, after the boot above has minted whatever material this topology needs, and
      // from that topology rather than from this process's own configuration -- see
      // clientIdentityFor.
      ControlPlaneApi api =
          new ControlPlaneApi(serverAddress, clientIdentityFor(topology, cluster, run));
      for (RenderedFile manifest : jarWorkloads) {
        pushArtifact(api, manifest, run);
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
      RenderedBundle rendered =
          dropUnsuppliedSecrets(BundleRenderer.render(bundle, merged, workspace), run);

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
      if (run.cancelRequested) {
        teardown(run);
      } else {
        fail(run, e.getMessage());
      }
    } catch (RuntimeException e) {
      // A cancelled run's own failure is the cancellation, whatever shape it arrived in -- an
      // interrupted sleep inside a readiness poll surfaces here as an ordinary runtime failure.
      if (run.cancelRequested) {
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
   * Aborts at a phase boundary, so a cancelled run stops before starting its next piece of work.
   */
  private static void requireNotCancelled(ActiveRun run) {
    if (run.cancelRequested) {
      throw new RunFailedException("run cancelled");
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

  /**
   * Every jar a run will push, read before anything is torn down. The push itself is the first step
   * after the boot, so a mistyped path used to be discovered only once the running cluster had
   * already been stopped and respawned -- the whole cost of a reboot for a typo the validate phase
   * can see.
   */
  private static void requireJarArtifactsReadable(List<RenderedFile> files) {
    for (RenderedFile manifest : jarSourcedWorkloads(files)) {
      Object artifactPath = readMapping(manifest.content()).get("artifactPath");
      if (!(artifactPath instanceof String pathString) || pathString.isBlank()) {
        continue;
      }
      Path jar = Path.of(pathString);
      if (!Files.isRegularFile(jar)) {
        throw new RunFailedException(
            "no jar at " + jar + " (from " + manifest.path() + ") -- check the artifact path");
      }
      try {
        ModuleArtifactReader.read(jar);
      } catch (RuntimeException notAModule) {
        throw new RunFailedException(
            "not a pushable module artifact at "
                + jar
                + " (from "
                + manifest.path()
                + "): "
                + notAModule.getMessage());
      }
    }
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
    api.postJson("/services", Json.write(body));
    run.log.append("applied service " + name);
  }

  private void applyNetworkPolicy(
      ControlPlaneApi api, RenderedFile manifest, Map<?, ?> mapping, ActiveRun run) {
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
      List<String> values = stringList(mapping.get(key));
      if (!values.isEmpty()) {
        body.put(key, values);
      }
    }
    api.postJson("/networkpolicies", Json.write(body));
    run.log.append("applied networkpolicy " + name);
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
      infos.add(new RunSnapshot.ProcessInfo(record.role(), address, true));
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
    volatile Optional<Integer> revision = Optional.empty();
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
