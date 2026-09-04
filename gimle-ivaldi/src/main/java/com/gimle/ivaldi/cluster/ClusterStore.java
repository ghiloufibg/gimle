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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Flat-file persistence for saved cluster connections, the exact {@link
 * com.gimle.ivaldi.blueprint.BlueprintStore} shape applied to a smaller document: one JSON file per
 * cluster under {@code root}, named {@code <id>.json}, id minted from the connection's {@code name}
 * field on create. The whole body is treated as opaque JSON the console owns -- this store only
 * reads {@code name}/{@code updatedAt} for the list's sort order.
 *
 * <p>Alongside each connection this store keeps one more thing the console's own {@code
 * ClusterConnection} document has no field for: the {@code topology.yaml} text a run actually last
 * applied to that cluster, as a sibling {@code <id>.topology.yaml} file. {@link
 * com.gimle.ivaldi.run.RunController} diffs a new run's rendered topology against this text to
 * decide whether the run can deploy onto the already-running process tree or must reboot it first
 * -- see that class for the decision itself. A cluster with no recorded topology yet (freshly
 * created, or just torn down by {@code DELETE /api/runs/current}) always reboots on its next run.
 */
public final class ClusterStore {

  private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final int MAX_ID_MINT_ATTEMPTS = 20;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String TOPOLOGY_SUFFIX = ".topology.yaml";

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
    String id = mintId(String.valueOf(json.getOrDefault("name", "cluster")));
    Map<String, Object> stamped = withId(json, id);
    write(fileFor(id), Json.write(stamped));
    return stamped;
  }

  /** Replaces (or creates) the cluster connection at {@code id} with {@code rawJson} verbatim. */
  public Map<String, Object> save(String id, String rawJson) {
    requireValidId(id);
    Map<String, Object> json = parseObject(rawJson);
    Map<String, Object> stamped = withId(json, id);
    write(fileFor(id), Json.write(stamped));
    return stamped;
  }

  public boolean delete(String id) {
    requireValidId(id);
    try {
      boolean deleted = Files.deleteIfExists(fileFor(id));
      Files.deleteIfExists(topologyFileFor(id));
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

  /** Forgets the applied topology: the next run against this cluster always reboots first. */
  public void clearAppliedTopology(String id) {
    requireValidId(id);
    try {
      Files.deleteIfExists(topologyFileFor(id));
    } catch (IOException e) {
      throw new UncheckedIOException("failed clearing applied topology for cluster " + id, e);
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
