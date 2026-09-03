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

  /** Creates a new blueprint from {@code rawJson}, minting an id from its {@code name} field. */
  public BlueprintSummary create(String rawJson) {
    Map<String, Object> json = parseObject(rawJson);
    String id = mintId(String.valueOf(json.getOrDefault("name", "blueprint")));
    write(id, rawJson);
    return summaryOf(id, json);
  }

  /** Replaces (or creates) the blueprint at {@code id} with {@code rawJson} verbatim. */
  public BlueprintSummary save(String id, String rawJson) {
    requireValidId(id);
    Map<String, Object> json = parseObject(rawJson);
    write(id, rawJson);
    return summaryOf(id, json);
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
