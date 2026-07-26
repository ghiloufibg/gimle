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

  public StateStore(Path root) {
    this.root = root;
    try {
      Files.createDirectories(deployments_dir());
      Files.createDirectories(assignments_dir());
      Files.createDirectories(nodes_dir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    load_all();
  }

  // ---- deployments ----

  public void put_deployment(DeploymentSpec spec) {
    write_atomically(deployment_file(spec.name()), deployment_to_yaml(spec));
    deployments.put(spec.name(), spec);
  }

  public Optional<DeploymentSpec> get_deployment(String name) {
    return Optional.ofNullable(deployments.get(name));
  }

  public List<DeploymentSpec> list_deployments() {
    return List.copyOf(deployments.values());
  }

  public void remove_deployment(String name) {
    delete_quietly(deployment_file(name));
    deployments.remove(name);
  }

  // ---- assignments ----

  public void put_assignment(InstanceAssignment assignment) {
    write_atomically(
        assignment_file(assignment.deploymentName(), assignment.instanceIndex()),
        assignment_to_yaml(assignment));
    assignments.put(
        assignment_key(assignment.deploymentName(), assignment.instanceIndex()), assignment);
  }

  public void remove_assignment(String deploymentName, int instanceIndex) {
    delete_quietly(assignment_file(deploymentName, instanceIndex));
    assignments.remove(assignment_key(deploymentName, instanceIndex));
  }

  public List<InstanceAssignment> list_assignments() {
    return List.copyOf(assignments.values());
  }

  public List<InstanceAssignment> list_assignments_for(String deploymentName) {
    return assignments.values().stream()
        .filter(a -> a.deploymentName().equals(deploymentName))
        .toList();
  }

  // ---- node registrations ----

  public void put_node_registration(NodeRegistration registration) {
    write_atomically(registration_file(registration.nodeId()), registration_to_yaml(registration));
    nodeRegistrations.put(registration.nodeId(), registration);
  }

  public Optional<NodeRegistration> get_node_registration(String nodeId) {
    return Optional.ofNullable(nodeRegistrations.get(nodeId));
  }

  public List<NodeRegistration> list_node_registrations() {
    return List.copyOf(nodeRegistrations.values());
  }

  // ---- node heartbeats ----

  public void put_node_heartbeat(NodeHeartbeat heartbeat) {
    Instant receivedAt = Instant.now();
    write_atomically(heartbeat_file(heartbeat.nodeId()), heartbeat_to_yaml(heartbeat, receivedAt));
    nodeHeartbeats.put(heartbeat.nodeId(), new ObservedHeartbeat(heartbeat, receivedAt));
  }

  public Optional<ObservedHeartbeat> get_node_heartbeat(String nodeId) {
    return Optional.ofNullable(nodeHeartbeats.get(nodeId));
  }

  public List<ObservedHeartbeat> list_node_heartbeats() {
    return List.copyOf(nodeHeartbeats.values());
  }

  // ---- disk layout ----

  private Path deployments_dir() {
    return root.resolve("deployments");
  }

  private Path assignments_dir() {
    return root.resolve("assignments");
  }

  private Path nodes_dir() {
    return root.resolve("nodes");
  }

  private Path deployment_file(String name) {
    return deployments_dir().resolve(name + ".yaml");
  }

  private Path assignment_file(String deploymentName, int instanceIndex) {
    return assignments_dir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
  }

  private Path registration_file(String nodeId) {
    return nodes_dir().resolve(nodeId).resolve("registration.yaml");
  }

  private Path heartbeat_file(String nodeId) {
    return nodes_dir().resolve(nodeId).resolve("heartbeat.yaml");
  }

  private static String assignment_key(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }

  private void load_all() {
    load_each(
        deployments_dir(),
        "*.yaml",
        file -> {
          DeploymentSpec spec =
              DeploymentManifestParser.parse(new ByteArrayInputStream(read(file)));
          deployments.put(spec.name(), spec);
        });
    load_each(
        assignments_dir(),
        "*/*.yaml",
        file -> {
          InstanceAssignment assignment = assignment_from_map(load_map(file));
          assignments.put(
              assignment_key(assignment.deploymentName(), assignment.instanceIndex()), assignment);
        });
    load_each(
        nodes_dir(),
        "*/registration.yaml",
        file -> {
          NodeRegistration registration = registration_from_map(load_map(file));
          nodeRegistrations.put(registration.nodeId(), registration);
        });
    load_each(
        nodes_dir(),
        "*/heartbeat.yaml",
        file -> {
          ObservedHeartbeat observed = heartbeat_from_map(load_map(file));
          nodeHeartbeats.put(observed.heartbeat().nodeId(), observed);
        });
  }

  private interface FileLoader {
    void load(Path file) throws IOException;
  }

  private static void load_each(Path dir, String glob, FileLoader loader) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.newDirectoryStream(dir, glob.contains("/") ? "*" : glob)) {
      for (Path entry : stream) {
        if (glob.contains("/")) {
          // one level of subdirectories, matching the remaining glob segment
          String childGlob = glob.substring(glob.indexOf('/') + 1);
          if (Files.isDirectory(entry)) {
            load_each(entry, childGlob, loader);
          }
        } else if (Files.isRegularFile(entry)) {
          loader.load(entry);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<?, ?> load_map(Path file) {
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

  private static void write_atomically(Path target, String content) {
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

  private static void delete_quietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- YAML (de)serialization: hand-rolled Map<->record mapping, same posture as
  // DeploymentManifestParser -- these are our own written files, not user-facing input, but kept
  // just as defensive (SafeConstructor on read) for consistency. ----

  private static String deployment_to_yaml(DeploymentSpec spec) {
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
    return new Yaml().dump(root);
  }

  private static String assignment_to_yaml(InstanceAssignment assignment) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", assignment.deploymentName());
    root.put("instanceIndex", assignment.instanceIndex());
    root.put("nodeId", assignment.nodeId());
    return new Yaml().dump(root);
  }

  private static InstanceAssignment assignment_from_map(Map<?, ?> map) {
    return new InstanceAssignment(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        (String) map.get("nodeId"));
  }

  private static String registration_to_yaml(NodeRegistration registration) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nodeId", registration.nodeId());
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put(
        "supportedTiers",
        registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
    root.put("capabilities", capabilities);
    return new Yaml().dump(root);
  }

  private static NodeRegistration registration_from_map(Map<?, ?> root) {
    String nodeId = (String) root.get("nodeId");
    Map<?, ?> capabilities = (Map<?, ?>) root.get("capabilities");
    List<?> tiers = (List<?>) capabilities.get("supportedTiers");
    Set<IsolationTier> supportedTiers =
        tiers.stream()
            .map(t -> IsolationTier.valueOf((String) t))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return new NodeRegistration(nodeId, new NodeCapabilities(supportedTiers));
  }

  private static String heartbeat_to_yaml(NodeHeartbeat heartbeat, Instant receivedAt) {
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
      instances.add(m);
    }
    root.put("instances", instances);
    return new Yaml().dump(root);
  }

  private static ObservedHeartbeat heartbeat_from_map(Map<?, ?> root) {
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
              (Boolean) m.get("ready")));
    }
    return new ObservedHeartbeat(new NodeHeartbeat(nodeId, capacity, instances), receivedAt);
  }
}
