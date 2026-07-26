package com.gimle.controlplane.store;

import com.gimle.controlplane.manifest.DeploymentManifestParser;
import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Embedded, single-node, file-backed state store (design §3): a directory of small YAML files, one
 * per resource, written via a temp file plus atomic move so a crash mid-write never leaves a torn
 * file where a reader could see it. An in-memory index is rebuilt from disk on construction; every
 * mutation writes through to disk before it's reflected in memory. Deliberately not an embedded SQL
 * engine or a hand-rolled binary format -- Phase 5 replaces this layer's internals with a
 * Raft-replicated log regardless of what's built here, so this is the least engineering that
 * survives a control-plane process restart, not a storage engine to build on.
 */
public final class StateStore {

  private final Path root;
  private final Map<String, DeploymentSpec> deployments = new ConcurrentHashMap<>();
  private final Map<String, InstanceAssignment> assignments = new ConcurrentHashMap<>();
  private final Map<String, NodeRegistration> nodeRegistrations = new ConcurrentHashMap<>();
  private final Map<String, ObservedHeartbeat> nodeHeartbeats = new ConcurrentHashMap<>();
  private final Map<String, Integer> rollingIndices = new ConcurrentHashMap<>();
  private final Map<String, Integer> effectiveReplicas = new ConcurrentHashMap<>();

  public StateStore(Path root) {
    this.root = root;
    try {
      Files.createDirectories(deploymentsDir());
      Files.createDirectories(assignmentsDir());
      Files.createDirectories(nodesDir());
      Files.createDirectories(rollingDir());
      Files.createDirectories(autoscaleDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    loadAll();
  }

  // ---- deployments ----

  public void putDeployment(DeploymentSpec spec) {
    writeAtomically(deploymentFile(spec.name()), deploymentToYaml(spec));
    deployments.put(spec.name(), spec);
  }

  public Optional<DeploymentSpec> getDeployment(String name) {
    return Optional.ofNullable(deployments.get(name));
  }

  public List<DeploymentSpec> listDeployments() {
    return List.copyOf(deployments.values());
  }

  public void removeDeployment(String name) {
    deleteQuietly(deploymentFile(name));
    deployments.remove(name);
    clearRollingIndex(name);
    deleteQuietly(effectiveReplicasFile(name));
    effectiveReplicas.remove(name);
  }

  // ---- assignments ----

  public void putAssignment(InstanceAssignment assignment) {
    writeAtomically(
        assignmentFile(assignment.deploymentName(), assignment.instanceIndex()),
        assignmentToYaml(assignment));
    assignments.put(
        assignmentKey(assignment.deploymentName(), assignment.instanceIndex()), assignment);
  }

  public void removeAssignment(String deploymentName, int instanceIndex) {
    deleteQuietly(assignmentFile(deploymentName, instanceIndex));
    assignments.remove(assignmentKey(deploymentName, instanceIndex));
  }

  public List<InstanceAssignment> listAssignments() {
    return List.copyOf(assignments.values());
  }

  public List<InstanceAssignment> listAssignmentsFor(String deploymentName) {
    return assignments.values().stream()
        .filter(a -> a.deploymentName().equals(deploymentName))
        .toList();
  }

  // ---- rolling-update bookkeeping (Phase 4 §9) ----

  /**
   * The logical instance index currently being replaced by a rolling update, if any -- persisted so
   * a reconciler restart mid-rollout resumes rather than starting a second one.
   */
  public void putRollingIndex(String deploymentName, int instanceIndex) {
    writeAtomically(rollingFile(deploymentName), rollingToYaml(instanceIndex));
    rollingIndices.put(deploymentName, instanceIndex);
  }

  public void clearRollingIndex(String deploymentName) {
    deleteQuietly(rollingFile(deploymentName));
    rollingIndices.remove(deploymentName);
  }

  public Optional<Integer> getRollingIndex(String deploymentName) {
    return Optional.ofNullable(rollingIndices.get(deploymentName));
  }

  // ---- autoscaling bookkeeping (Phase 4 §10) ----

  /**
   * The autoscaler's current target replica count, read by {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler} in place of {@code
   * DeploymentSpec#replicas()} whenever a deployment carries an {@code autoscale} policy.
   */
  public void putEffectiveReplicas(String deploymentName, int replicas) {
    writeAtomically(effectiveReplicasFile(deploymentName), effectiveReplicasToYaml(replicas));
    effectiveReplicas.put(deploymentName, replicas);
  }

  public Optional<Integer> getEffectiveReplicas(String deploymentName) {
    return Optional.ofNullable(effectiveReplicas.get(deploymentName));
  }

  // ---- node registrations ----

  public void putNodeRegistration(NodeRegistration registration) {
    writeAtomically(registrationFile(registration.nodeId()), registrationToYaml(registration));
    nodeRegistrations.put(registration.nodeId(), registration);
  }

  public Optional<NodeRegistration> getNodeRegistration(String nodeId) {
    return Optional.ofNullable(nodeRegistrations.get(nodeId));
  }

  public List<NodeRegistration> listNodeRegistrations() {
    return List.copyOf(nodeRegistrations.values());
  }

  // ---- node heartbeats ----

  public void putNodeHeartbeat(NodeHeartbeat heartbeat) {
    Instant receivedAt = Instant.now();
    writeAtomically(heartbeatFile(heartbeat.nodeId()), heartbeatToYaml(heartbeat, receivedAt));
    nodeHeartbeats.put(heartbeat.nodeId(), new ObservedHeartbeat(heartbeat, receivedAt));
  }

  public Optional<ObservedHeartbeat> getNodeHeartbeat(String nodeId) {
    return Optional.ofNullable(nodeHeartbeats.get(nodeId));
  }

  public List<ObservedHeartbeat> listNodeHeartbeats() {
    return List.copyOf(nodeHeartbeats.values());
  }

  // ---- disk layout ----

  private Path deploymentsDir() {
    return root.resolve("deployments");
  }

  private Path assignmentsDir() {
    return root.resolve("assignments");
  }

  private Path nodesDir() {
    return root.resolve("nodes");
  }

  private Path rollingDir() {
    return root.resolve("rolling");
  }

  private Path rollingFile(String deploymentName) {
    return rollingDir().resolve(deploymentName + ".yaml");
  }

  private Path autoscaleDir() {
    return root.resolve("autoscale");
  }

  private Path effectiveReplicasFile(String deploymentName) {
    return autoscaleDir().resolve(deploymentName + ".yaml");
  }

  private Path deploymentFile(String name) {
    return deploymentsDir().resolve(name + ".yaml");
  }

  private Path assignmentFile(String deploymentName, int instanceIndex) {
    return assignmentsDir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
  }

  private Path registrationFile(String nodeId) {
    return nodesDir().resolve(nodeId).resolve("registration.yaml");
  }

  private Path heartbeatFile(String nodeId) {
    return nodesDir().resolve(nodeId).resolve("heartbeat.yaml");
  }

  private static String assignmentKey(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }

  private void loadAll() {
    loadEach(
        deploymentsDir(),
        "*.yaml",
        file -> {
          DeploymentSpec spec =
              DeploymentManifestParser.parse(new ByteArrayInputStream(read(file)));
          deployments.put(spec.name(), spec);
        });
    loadEach(
        assignmentsDir(),
        "*/*.yaml",
        file -> {
          InstanceAssignment assignment = assignmentFromMap(loadMap(file));
          assignments.put(
              assignmentKey(assignment.deploymentName(), assignment.instanceIndex()), assignment);
        });
    loadEach(
        nodesDir(),
        "*/registration.yaml",
        file -> {
          NodeRegistration registration = registrationFromMap(loadMap(file));
          nodeRegistrations.put(registration.nodeId(), registration);
        });
    loadEach(
        nodesDir(),
        "*/heartbeat.yaml",
        file -> {
          ObservedHeartbeat observed = heartbeatFromMap(loadMap(file));
          nodeHeartbeats.put(observed.heartbeat().nodeId(), observed);
        });
    loadEach(
        rollingDir(),
        "*.yaml",
        file -> {
          String deploymentName = file.getFileName().toString().replaceFirst("\\.yaml$", "");
          Map<?, ?> map = loadMap(file);
          rollingIndices.put(deploymentName, ((Number) map.get("instanceIndex")).intValue());
        });
    loadEach(
        autoscaleDir(),
        "*.yaml",
        file -> {
          String deploymentName = file.getFileName().toString().replaceFirst("\\.yaml$", "");
          Map<?, ?> map = loadMap(file);
          effectiveReplicas.put(deploymentName, ((Number) map.get("replicas")).intValue());
        });
  }

  private interface FileLoader {
    void load(Path file) throws IOException;
  }

  private static void loadEach(Path dir, String glob, FileLoader loader) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.newDirectoryStream(dir, glob.contains("/") ? "*" : glob)) {
      for (Path entry : stream) {
        if (glob.contains("/")) {
          // one level of subdirectories, matching the remaining glob segment
          String childGlob = glob.substring(glob.indexOf('/') + 1);
          if (Files.isDirectory(entry)) {
            loadEach(entry, childGlob, loader);
          }
        } else if (Files.isRegularFile(entry)) {
          loader.load(entry);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<?, ?> loadMap(Path file) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw = yaml.load(new ByteArrayInputStream(read(file)));
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalStateException("expected a YAML mapping in " + file);
    }
    return map;
  }

  private static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAtomically(Path target, String content) {
    try {
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- YAML (de)serialization: hand-rolled Map<->record mapping, same posture as
  // DeploymentManifestParser -- these are our own written files, not user-facing input, but kept
  // just as defensive (SafeConstructor on read) for consistency. ----

  private static String deploymentToYaml(DeploymentSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", spec.name());
    Map<String, Object> module = new LinkedHashMap<>();
    module.put("name", spec.moduleId().name());
    module.put("version", spec.moduleId().version().toString());
    root.put("module", module);
    root.put("artifactPath", spec.artifactPath());
    root.put("replicas", spec.replicas());
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("antiAffinity", spec.placement().antiAffinityAcrossNodes());
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    root.put("placement", placement);
    spec.autoscale()
        .ifPresent(
            policy -> {
              Map<String, Object> autoscale = new LinkedHashMap<>();
              autoscale.put("minReplicas", policy.minReplicas());
              autoscale.put("maxReplicas", policy.maxReplicas());
              autoscale.put("targetCpuUtilizationPercent", policy.targetCpuUtilizationPercent());
              root.put("autoscale", autoscale);
            });
    return new Yaml().dump(root);
  }

  private static String rollingToYaml(int instanceIndex) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("instanceIndex", instanceIndex);
    return new Yaml().dump(root);
  }

  private static String effectiveReplicasToYaml(int replicas) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("replicas", replicas);
    return new Yaml().dump(root);
  }

  private static String assignmentToYaml(InstanceAssignment assignment) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", assignment.deploymentName());
    root.put("instanceIndex", assignment.instanceIndex());
    root.put("nodeId", assignment.nodeId());
    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", assignment.moduleId().name());
    moduleId.put("version", assignment.moduleId().version().toString());
    root.put("moduleId", moduleId);
    root.put("artifactPath", assignment.artifactPath());
    return new Yaml().dump(root);
  }

  private static InstanceAssignment assignmentFromMap(Map<?, ?> map) {
    Map<?, ?> moduleIdMap = (Map<?, ?>) map.get("moduleId");
    ModuleId moduleId =
        new ModuleId(
            (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
    Object artifactPath = map.get("artifactPath");
    return new InstanceAssignment(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        (String) map.get("nodeId"),
        moduleId,
        artifactPath == null ? "" : (String) artifactPath);
  }

  private static String registrationToYaml(NodeRegistration registration) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nodeId", registration.nodeId());
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put(
        "supportedTiers",
        registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
    root.put("capabilities", capabilities);
    return new Yaml().dump(root);
  }

  private static NodeRegistration registrationFromMap(Map<?, ?> root) {
    String nodeId = (String) root.get("nodeId");
    Map<?, ?> capabilities = (Map<?, ?>) root.get("capabilities");
    List<?> tiers = (List<?>) capabilities.get("supportedTiers");
    Set<IsolationTier> supportedTiers =
        tiers.stream()
            .map(t -> IsolationTier.valueOf((String) t))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return new NodeRegistration(nodeId, new NodeCapabilities(supportedTiers));
  }

  private static String heartbeatToYaml(NodeHeartbeat heartbeat, Instant receivedAt) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nodeId", heartbeat.nodeId());
    root.put("receivedAt", receivedAt.toString());
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", heartbeat.capacity().totalMemoryBytes());
    capacity.put("assignedMemoryBytes", heartbeat.capacity().assignedMemoryBytes());
    capacity.put("totalCpuMillicores", heartbeat.capacity().totalCpuMillicores());
    capacity.put("assignedCpuMillicores", heartbeat.capacity().assignedCpuMillicores());
    root.put("capacity", capacity);
    List<Map<String, Object>> instances = new ArrayList<>();
    for (InstanceObservation obs : heartbeat.instances()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("deploymentName", obs.deploymentName());
      m.put("instanceIndex", obs.instanceIndex());
      Map<String, Object> moduleId = new LinkedHashMap<>();
      moduleId.put("name", obs.moduleId().name());
      moduleId.put("version", obs.moduleId().version().toString());
      m.put("moduleId", moduleId);
      m.put("lifecycleState", obs.lifecycleState());
      m.put("alive", obs.alive());
      m.put("ready", obs.ready());
      m.put("requestRatePerSecond", obs.requestRatePerSecond());
      m.put("queueDepth", obs.queueDepth());
      m.put("cpuMillicoresUsed", obs.cpuMillicoresUsed());
      m.put("memoryBytesUsed", obs.memoryBytesUsed());
      instances.add(m);
    }
    root.put("instances", instances);
    return new Yaml().dump(root);
  }

  private static Number numberOrDefault(Object value, Number defaultValue) {
    return value instanceof Number number ? number : defaultValue;
  }

  private static ObservedHeartbeat heartbeatFromMap(Map<?, ?> root) {
    String nodeId = (String) root.get("nodeId");
    Instant receivedAt = Instant.parse((String) root.get("receivedAt"));
    Map<?, ?> capacityMap = (Map<?, ?>) root.get("capacity");
    ResourceUsageSnapshot capacity =
        new ResourceUsageSnapshot(
            ((Number) capacityMap.get("totalMemoryBytes")).longValue(),
            ((Number) capacityMap.get("assignedMemoryBytes")).longValue(),
            ((Number) capacityMap.get("totalCpuMillicores")).longValue(),
            ((Number) capacityMap.get("assignedCpuMillicores")).longValue());
    List<?> instancesList = (List<?>) root.get("instances");
    List<InstanceObservation> instances = new ArrayList<>();
    for (Object o : instancesList) {
      Map<?, ?> m = (Map<?, ?>) o;
      Map<?, ?> moduleIdMap = (Map<?, ?>) m.get("moduleId");
      ModuleId moduleId =
          new ModuleId(
              (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
      instances.add(
          new InstanceObservation(
              (String) m.get("deploymentName"),
              ((Number) m.get("instanceIndex")).intValue(),
              moduleId,
              (String) m.get("lifecycleState"),
              (Boolean) m.get("alive"),
              (Boolean) m.get("ready"),
              numberOrDefault(m.get("requestRatePerSecond"), 0.0).doubleValue(),
              numberOrDefault(m.get("queueDepth"), 0).intValue(),
              numberOrDefault(m.get("cpuMillicoresUsed"), 0L).longValue(),
              numberOrDefault(m.get("memoryBytesUsed"), 0L).longValue()));
    }
    return new ObservedHeartbeat(new NodeHeartbeat(nodeId, capacity, instances), receivedAt);
  }
}
