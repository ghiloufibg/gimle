package com.gimle.ivaldi.blueprint;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Flat-file persistence for Blueprint documents: one JSON file per blueprint under {@code root},
 * named {@code <id>.json}. The store treats a Blueprint's body as opaque JSON -- it never parses
 * the node/edge graph the console owns -- and reads only the handful of top-level string fields
 * ({@code name}, {@code version}, {@code updatedAt}) {@link BlueprintSummary} needs, so this class
 * stays correct across schema changes the console makes to the rest of the document. Writes are
 * write-tmp-then-atomic-move: a reader must never observe a half-written file.
 */
public final class BlueprintStore {

  private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final int MAX_ID_MINT_ATTEMPTS = 20;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path root;

  public BlueprintStore(Path root) {
    this.root = root;
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new UncheckedIOException("failed creating blueprint store directory: " + root, e);
    }
  }

  public List<BlueprintSummary> list() {
    List<Path> files;
    try (Stream<Path> entries = Files.list(root)) {
      files = entries.filter(p -> fileNameOf(p).endsWith(".json")).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("failed listing blueprints under " + root, e);
    }
    List<BlueprintSummary> summaries = new ArrayList<>();
    for (Path file : files) {
      String id = idFromFileName(fileNameOf(file));
      readJson(file).ifPresent(json -> summaries.add(summaryOf(id, json)));
    }
    summaries.sort(Comparator.comparing(BlueprintSummary::updatedAt).reversed());
    return summaries;
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
      throw new UncheckedIOException("failed reading blueprint " + id, e);
    }
  }

  /**
   * Creates a new blueprint from {@code rawJson}, honouring the {@code id} the body already carries
   * when that id is well-formed and free, and otherwise minting one from the {@code name} field.
   *
   * <p>Honouring the body's own id matters because the console mints one client-side before it ever
   * POSTs, and addresses every later save by it. Ignoring it and minting a second, different id
   * server-side meant the store answered with one id while the document it had just written claimed
   * another: the console navigated to the id it was handed, saved to the id inside its own
   * document, and ended up with two divergent records for one blueprint -- the URL it had just
   * opened frozen at creation-time content, every subsequent edit landing invisibly under the other
   * id. Whichever id wins, it is stamped into the stored body, so a blueprint's own {@code id}
   * field and the id it is addressed by can never disagree again.
   */
  public BlueprintSummary create(String rawJson) {
    Map<String, Object> json = parseObject(rawJson);
    requireBlueprintShape(json);
    String id =
        requestedId(json)
            .orElseGet(() -> mintId(String.valueOf(json.getOrDefault("name", "blueprint"))));
    Map<String, Object> stamped = withId(json, id);
    write(id, Json.write(stamped));
    return summaryOf(id, stamped);
  }

  /** Replaces (or creates) the blueprint at {@code id}, stamping {@code id} into the body. */
  public BlueprintSummary save(String id, String rawJson) {
    requireValidId(id);
    Map<String, Object> json = parseObject(rawJson);
    requireBlueprintShape(json);
    Map<String, Object> stamped = withId(json, id);
    write(id, Json.write(stamped));
    return summaryOf(id, stamped);
  }

  public boolean delete(String id) {
    requireValidId(id);
    try {
      return Files.deleteIfExists(fileFor(id));
    } catch (IOException e) {
      throw new UncheckedIOException("failed deleting blueprint " + id, e);
    }
  }

  private void write(String id, String rawJson) {
    Path target = fileFor(id);
    Path tmp = root.resolve(id + ".json.tmp-" + RANDOM.nextInt(Integer.MAX_VALUE));
    try {
      Files.writeString(tmp, rawJson, StandardCharsets.UTF_8);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("failed writing blueprint " + id, e);
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // Best-effort cleanup of a leftover temp file; a stray one costs disk space, not
        // correctness -- the atomic move above already succeeded or failed cleanly by this point.
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
      throw new IllegalArgumentException("blueprint body is not valid JSON: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("blueprint body must be a JSON object");
    }
    return Json.asObject(parsed);
  }

  private static BlueprintSummary summaryOf(String id, Map<String, Object> json) {
    return new BlueprintSummary(
        id,
        String.valueOf(json.getOrDefault("name", id)),
        String.valueOf(json.getOrDefault("version", "")),
        String.valueOf(json.getOrDefault("updatedAt", "")));
  }

  private Path fileFor(String id) {
    return root.resolve(id + ".json");
  }

  private static String idFromFileName(String fileName) {
    return fileName.substring(0, fileName.length() - ".json".length());
  }

  /** {@code Path#getFileName()} is only ever null for a root path, never for a directory entry. */
  private static String fileNameOf(Path path) {
    return Objects.requireNonNull(path.getFileName(), () -> "path has no file name: " + path)
        .toString();
  }

  /**
   * The body's own {@code id}, when it is well-formed and not already taken. A collision falls back
   * to minting rather than overwriting: a POST is a create, and must never silently replace an
   * existing blueprint just because a client reused an id.
   */
  /**
   * Refuses a document that is syntactically JSON but structurally not a blueprint. Without this
   * any object at all is stored verbatim and every screen that reads the list then throws on it --
   * one malformed POST leaves the whole console unusable, and the only way back is deleting the
   * document over this same API. Checked here rather than in the HTTP layer so a bad document
   * cannot reach the store by any route.
   */
  private static void requireBlueprintShape(Map<String, Object> json) {
    Object name = json.get("name");
    if (!(name instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("blueprint has no 'name'");
    }
    requireNodeList(json, "nodes");
    requireNodeList(json, "edges");
  }

  private static void requireNodeList(Map<String, Object> json, String field) {
    Object value = json.get(field);
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("blueprint has no '" + field + "' list");
    }
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof Map<?, ?> entry)) {
        throw new IllegalArgumentException(field + "[" + i + "] is not an object");
      }
      if (!(entry.get("kind") instanceof String kind) || kind.isBlank()) {
        throw new IllegalArgumentException(field + "[" + i + "] has no 'kind'");
      }
    }
  }

  private Optional<String> requestedId(Map<String, Object> json) {
    Object raw = json.get("id");
    if (!(raw instanceof String id) || !ID_PATTERN.matcher(id).matches()) {
      return Optional.empty();
    }
    return Files.exists(fileFor(id)) ? Optional.empty() : Optional.of(id);
  }

  private static Map<String, Object> withId(Map<String, Object> json, String id) {
    Map<String, Object> copy = new LinkedHashMap<>(json);
    copy.put("id", id);
    return copy;
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
    throw new IllegalStateException("could not mint a unique blueprint id for: " + name);
  }

  private static String slugify(String name) {
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    return slug.isBlank() ? "blueprint" : slug;
  }

  private static void requireValidId(String id) {
    if (id == null || !ID_PATTERN.matcher(id).matches()) {
      throw new IllegalArgumentException("invalid blueprint id: " + id);
    }
  }
}
