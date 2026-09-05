package com.gimle.ivaldi.cluster;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Flat-file persistence for saved cluster connections, the exact {@link
 * com.gimle.ivaldi.blueprint.BlueprintStore} shape applied to a smaller document: one JSON file per
 * cluster under {@code root}, named {@code <id>.json}, id minted from the connection's {@code name}
 * field on create. The body is otherwise treated as opaque JSON the console owns -- beyond {@code
 * name}/{@code updatedAt} for the list's sort order, the only field this store looks at is {@code
 * controlPlaneUrl}, which it refuses to write blank (see {@link #requireControlPlaneUrl}).
 *
 * <p>Alongside each connection this store keeps two more things the console's own {@code
 * ClusterConnection} document has no field for. One is the {@code topology.yaml} text a run
 * actually last applied to that cluster, as a sibling {@code <id>.topology.yaml} file. {@link
 * com.gimle.ivaldi.run.RunController} diffs a new run's rendered topology against this text to
 * decide whether the run can deploy onto the already-running process tree or must reboot it first
 * -- see that class for the decision itself. A cluster with no recorded topology yet (freshly
 * created, or just torn down because its last deployment stopped) always reboots on its next run.
 *
 * <p>The other is the set of blueprint ids with a deployment currently recorded against that
 * cluster, as a sibling {@code <id>.deployments} file -- one cluster's infra, once up, can host any
 * number of blueprints' own deployments (see {@code RunController}'s "Deploy-only vs. reboot"
 * section), so there is no single "owning" blueprint any more, only the set of blueprints currently
 * deployed there. This is what {@code RunController}'s own restart re-attachment reads to rebuild
 * one tracked run per deployment instead of attributing the whole shared cluster to just one of
 * them.
 */
public final class ClusterStore {

  private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final int MAX_ID_MINT_ATTEMPTS = 20;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String TOPOLOGY_SUFFIX = ".topology.yaml";
  private static final String DEPLOYMENTS_SUFFIX = ".deployments";

  private final Path root;

  public ClusterStore(Path root) {
    this.root = root;
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new UncheckedIOException("failed creating cluster store directory: " + root, e);
    }
  }

  public List<Map<String, Object>> list() {
    List<Path> files;
    try (Stream<Path> entries = Files.list(root)) {
      files =
          entries
              .filter(p -> fileNameOf(p).endsWith(".json") && !fileNameOf(p).endsWith(".json.tmp"))
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("failed listing clusters under " + root, e);
    }
    List<Map<String, Object>> clusters = new ArrayList<>();
    for (Path file : files) {
      readJson(file).ifPresent(clusters::add);
    }
    clusters.sort(
        Comparator.comparing(
                (Map<String, Object> c) -> String.valueOf(c.getOrDefault("updatedAt", "")))
            .reversed());
    return clusters;
  }

  public Optional<String> get(String id) {
    requireValidId(id);
    Path file = fileFor(id);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading cluster " + id, e);
    }
  }

  /** Creates a new cluster connection from {@code rawJson}, minting an id from its name field. */
  public Map<String, Object> create(String rawJson) {
    Map<String, Object> json = parseObject(rawJson);
    requireControlPlaneUrl(json);
    String id = mintId(String.valueOf(json.getOrDefault("name", "cluster")));
    Map<String, Object> stamped = withId(json, id);
    write(fileFor(id), Json.write(stamped));
    return stamped;
  }

  /** Replaces (or creates) the cluster connection at {@code id} with {@code rawJson} verbatim. */
  public Map<String, Object> save(String id, String rawJson) {
    requireValidId(id);
    Map<String, Object> json = parseObject(rawJson);
    requireControlPlaneUrl(json);
    Map<String, Object> stamped = withId(json, id);
    write(fileFor(id), Json.write(stamped));
    return stamped;
  }

  public boolean delete(String id) {
    requireValidId(id);
    try {
      boolean deleted = Files.deleteIfExists(fileFor(id));
      Files.deleteIfExists(topologyFileFor(id));
      Files.deleteIfExists(deploymentsFileFor(id));
      return deleted;
    } catch (IOException e) {
      throw new UncheckedIOException("failed deleting cluster " + id, e);
    }
  }

  /** The {@code topology.yaml} text a run last actually applied to this cluster, if any. */
  public Optional<String> appliedTopology(String id) {
    requireValidId(id);
    Path file = topologyFileFor(id);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading applied topology for cluster " + id, e);
    }
  }

  /** Records {@code topologyYaml} as the text a run just successfully applied to this cluster. */
  public void recordAppliedTopology(String id, String topologyYaml) {
    requireValidId(id);
    write(topologyFileFor(id), topologyYaml);
  }

  /**
   * Forgets the applied topology and every deployment recorded against it: the next run against
   * this cluster always reboots first, and a reboot tears down the whole process tree -- the state
   * every previously-recorded deployment lived in -- so none of them survive it either, whichever
   * blueprint's run happens to be the one driving the reboot.
   */
  public void clearAppliedTopology(String id) {
    requireValidId(id);
    try {
      Files.deleteIfExists(topologyFileFor(id));
      Files.deleteIfExists(deploymentsFileFor(id));
    } catch (IOException e) {
      throw new UncheckedIOException("failed clearing applied topology for cluster " + id, e);
    }
  }

  /**
   * Blueprint ids with a deployment currently recorded against this cluster.
   *
   * <p>Recorded beside the applied topology because it is the only durable link between the two:
   * run state lives in memory, so after a restart a recovered cluster's live process tree could be
   * re-adopted but not attributed to anything, and no screen can show a blueprint as running
   * without knowing which cluster is its own. A cluster's infra, once up, can host any number of
   * blueprints' own deployments (see {@code RunController}), so this is a set, not a single owner.
   */
  public Set<String> deployments(String id) {
    requireValidId(id);
    Path file = deploymentsFileFor(id);
    if (!Files.exists(file)) {
      return Set.of();
    }
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .collect(Collectors.toCollection(LinkedHashSet::new));
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading deployments for cluster " + id, e);
    }
  }

  /** Records that {@code blueprintId} now has a deployment against this cluster. */
  public void recordDeployment(String id, String blueprintId) {
    requireValidId(id);
    Set<String> updated = new LinkedHashSet<>(deployments(id));
    updated.add(blueprintId);
    write(deploymentsFileFor(id), String.join("\n", updated));
  }

  /**
   * Forgets {@code blueprintId}'s own deployment against this cluster -- its run was stopped and
   * (best-effort) its release undeployed, but the cluster's infra, and any other blueprint's own
   * deployment on it, may still be up.
   */
  public void removeDeployment(String id, String blueprintId) {
    requireValidId(id);
    Set<String> updated = new LinkedHashSet<>(deployments(id));
    if (updated.remove(blueprintId)) {
      write(deploymentsFileFor(id), String.join("\n", updated));
    }
  }

  private static void write(Path target, String content) {
    Path tmp =
        target.resolveSibling(fileNameOf(target) + ".tmp-" + RANDOM.nextInt(Integer.MAX_VALUE));
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("failed writing " + target, e);
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // Best-effort cleanup of a leftover temp file; the atomic move above already succeeded or
        // failed cleanly by this point.
      }
    }
  }

  private Optional<Map<String, Object>> readJson(Path file) {
    try {
      return Optional.of(parseObject(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      // A corrupt or unreadable file is skipped from listings rather than failing the whole list.
      return Optional.empty();
    }
  }

  /**
   * The one field of an otherwise-opaque body this store refuses to accept empty: a saved cluster
   * connection whose whole purpose is to name a control plane, without one, is a record that can
   * never run anything. Accepting it silently only moved the failure to the far end of a run that
   * had already booted a platform first (see {@code RunController}'s own check of the same field).
   */
  private static void requireControlPlaneUrl(Map<String, Object> json) {
    Object url = json.get("controlPlaneUrl");
    if (url == null || String.valueOf(url).isBlank()) {
      throw new IllegalArgumentException(
          "a cluster connection needs a non-blank 'controlPlaneUrl', e.g. 127.0.0.1:8080");
    }
  }

  private static Map<String, Object> parseObject(String rawJson) {
    Object parsed;
    try {
      parsed = Json.parse(rawJson);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("cluster body is not valid JSON: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("cluster body must be a JSON object");
    }
    return Json.asObject(parsed);
  }

  private static Map<String, Object> withId(Map<String, Object> json, String id) {
    Map<String, Object> copy = new java.util.LinkedHashMap<>(json);
    copy.put("id", id);
    return copy;
  }

  private Path fileFor(String id) {
    return root.resolve(id + ".json");
  }

  private Path topologyFileFor(String id) {
    return root.resolve(id + TOPOLOGY_SUFFIX);
  }

  private Path deploymentsFileFor(String id) {
    return root.resolve(id + DEPLOYMENTS_SUFFIX);
  }

  /** {@code Path#getFileName()} is only ever null for a root path, never for a directory entry. */
  private static String fileNameOf(Path path) {
    return Objects.requireNonNull(path.getFileName(), () -> "path has no file name: " + path)
        .toString();
  }

  private String mintId(String name) {
    String slug = slugify(name);
    for (int attempt = 0; attempt < MAX_ID_MINT_ATTEMPTS; attempt++) {
      String candidate =
          attempt == 0 ? slug : slug + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF));
      if (!Files.exists(fileFor(candidate))) {
        return candidate;
      }
    }
    throw new IllegalStateException("could not mint a unique cluster id for: " + name);
  }

  private static String slugify(String name) {
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    return slug.isBlank() ? "cluster" : slug;
  }

  private static void requireValidId(String id) {
    if (id == null || !ID_PATTERN.matcher(id).matches()) {
      throw new IllegalArgumentException("invalid cluster id: " + id);
    }
  }
}
