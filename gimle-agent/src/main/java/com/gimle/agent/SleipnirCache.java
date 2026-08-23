package com.gimle.agent;

import com.gimle.core.hash.Sha256;
import com.gimle.core.protocol.Json;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The on-disk state of Sleipnir's worker AOT cache: one {@code worker-<key>.aot} file per node
 * (never per module/tenant -- classes loaded by a hosted module's own {@code ModuleLayer} get no
 * AOT benefit at all, so there is exactly one cache key live per agent process), with a {@code
 * worker-<key>.meta.json} sidecar committed first so a reader can never observe a {@code .aot} with
 * no matching {@code .meta.json} -- an incomplete write, not a valid cache.
 *
 * <p>Owns key computation, file-state (lookup/commit), and sweep -- kept separate from {@link
 * SleipnirTrainer}, which owns only the slow, background, one-shot training subprocess
 * orchestration and calls into this class to commit its result. {@code cacheFor} is called on
 * {@code startInstance}'s hot path on every instance spawn and must stay fast and synchronous; the
 * key itself is memoized after its first computation since {@code commandTail} is invariant for
 * this agent's whole process lifetime (one worker binary/classpath per node).
 */
final class SleipnirCache {

  private static final Logger log = LoggerFactory.getLogger(SleipnirCache.class);

  private final Path cacheRoot;
  private final Map<String, SupervisedInstance> supervised;
  private final String javaExecutable;

  // null: not yet computed. Optional.empty(): computed once, ineligible. Optional.of(key):
  // computed once, eligible. Distinguishing "not yet computed" from "computed, ineligible" is
  // exactly why this isn't itself an Optional -- Optional.empty() is a legitimate memoized value.
  private volatile Optional<String> memoizedKey;

  SleipnirCache(Path cacheRoot, Map<String, SupervisedInstance> supervised, String javaExecutable) {
    this.cacheRoot = cacheRoot;
    this.supervised = supervised;
    this.javaExecutable = javaExecutable;
  }

  /**
   * The current cache key for {@code commandTail}, or empty when any classpath entry it carries
   * isn't a jar (JEP 483's own eligibility constraint -- a directory on the classpath disqualifies
   * the whole cache). The single computation both {@link SleipnirTrainer}'s ineligibility check and
   * the key algorithm share, so they can never disagree about eligibility. Memoized after the first
   * call; the ineligibility log line (when applicable) is therefore emitted at most once per agent
   * process, satisfying "log once at INFO" without separate log-dedup bookkeeping.
   */
  Optional<String> keyFor(List<String> commandTail) {
    Optional<String> existing = memoizedKey;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (memoizedKey == null) {
        memoizedKey = computeKey(commandTail);
      }
      return memoizedKey;
    }
  }

  /**
   * The current worker AOT cache file for {@code commandTail}, present only when both it and its
   * {@code meta.json} sidecar exist. Fast and synchronous -- at most two {@code
   * Files.isRegularFile} stats beyond the (memoized) key lookup, safe to call on {@code
   * startInstance}'s hot path.
   */
  Optional<Path> cacheFor(List<String> commandTail) {
    return keyFor(commandTail).flatMap(this::pathIfComplete);
  }

  private Optional<Path> pathIfComplete(String key) {
    Path aot = aotPath(key);
    Path meta = metaPath(key);
    return Files.isRegularFile(aot) && Files.isRegularFile(meta)
        ? Optional.of(aot)
        : Optional.empty();
  }

  /**
   * A fresh temp path under {@code cacheRoot} for a training run to write {@code
   * -XX:AOTCacheOutput=} into -- {@link SleipnirTrainer} never picks cache-directory paths itself,
   * keeping all path-naming knowledge in this one class (the same single-owner discipline {@code
   * ArtifactPullCache} already establishes for its own cache directory).
   */
  Path newTrainingOutputPath(String key) throws IOException {
    Files.createDirectories(cacheRoot);
    return cacheRoot.resolve("worker-" + key + ".aot.tmp-" + UUID.randomUUID());
  }

  /**
   * Commits a verified, fully-written training output into place: {@code meta.json} first, then the
   * {@code .aot} itself, both via atomic rename -- mirrors {@code ArtifactPullCache}'s own
   * temp-then-atomic-rename commit discipline. Order matters: a reader can never observe a {@code
   * .aot} with no matching {@code .meta.json}.
   */
  void commit(String key, Path trainedTmpAot) {
    try {
      Files.createDirectories(cacheRoot);
      long sizeBytes = Files.size(trainedTmpAot);
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("key", key);
      meta.put("javaExecutable", javaExecutable);
      meta.put("jdkVersion", javaVersionString());
      meta.put("createdAt", Instant.now().toString());
      meta.put("sizeBytes", sizeBytes);
      Path metaTmp = cacheRoot.resolve("worker-" + key + ".meta.json.tmp-" + UUID.randomUUID());
      Files.writeString(metaTmp, Json.write(meta));
      Files.move(
          metaTmp,
          metaPath(key),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          trainedTmpAot,
          aotPath(key),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      log.info("Sleipnir: committed AOT cache for key {} ({} bytes)", key, sizeBytes);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to commit Sleipnir AOT cache for key " + key, e);
    }
  }

  /**
   * Deletes every cache entry except the current key's pair and any key still referenced by a live
   * worker (via {@link SupervisedInstance#aotCacheKey}), plus unconditionally deletes any orphaned
   * {@code .tmp-*} file left by a training or commit run that never reached its final rename.
   * Best-effort -- a failed delete is logged and skipped, never thrown; an unrecognized file in
   * {@code cacheRoot} (not matching the {@code worker-*.aot}/{@code worker-*.meta.json}/{@code
   * *.tmp-*} naming this class itself uses) is left untouched rather than guessed at.
   */
  void sweep() {
    if (!Files.isDirectory(cacheRoot)) {
      return;
    }
    Set<String> liveKeys = new HashSet<>();
    Optional<String> current = memoizedKey;
    if (current != null) {
      current.ifPresent(liveKeys::add);
    }
    for (SupervisedInstance instance : supervised.values()) {
      instance.aotCacheKey.ifPresent(liveKeys::add);
    }
    try (Stream<Path> files = Files.list(cacheRoot)) {
      for (Path file : files.toList()) {
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
          continue; // Files.list never actually yields a no-name-element path; guard anyway
        }
        String name = fileNamePath.toString();
        if (name.contains(".tmp-")) {
          deleteQuietly(file);
          continue;
        }
        String key = keyFromFileName(name);
        if (key != null && !liveKeys.contains(key)) {
          deleteQuietly(file);
        }
      }
    } catch (IOException e) {
      log.warn("Sleipnir: sweep of {} failed: {}", cacheRoot, e.getMessage());
    }
  }

  private void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Sleipnir: failed to delete stale cache file {}: {}", file, e.getMessage());
    }
  }

  private static String keyFromFileName(String fileName) {
    if (fileName.startsWith("worker-") && fileName.endsWith(".aot")) {
      return fileName.substring("worker-".length(), fileName.length() - ".aot".length());
    }
    if (fileName.startsWith("worker-") && fileName.endsWith(".meta.json")) {
      return fileName.substring("worker-".length(), fileName.length() - ".meta.json".length());
    }
    return null;
  }

  private Path aotPath(String key) {
    return cacheRoot.resolve("worker-" + key + ".aot");
  }

  private Path metaPath(String key) {
    return cacheRoot.resolve("worker-" + key + ".meta.json");
  }

  private Optional<String> computeKey(List<String> commandTail) {
    Optional<List<Path>> parsed = classpathEntries(commandTail);
    if (parsed.isEmpty()) {
      log.info("Sleipnir: AOT cache ineligible: no classpath found on the worker command line");
      return Optional.empty();
    }
    List<Path> entries = parsed.get();
    for (Path entry : entries) {
      if (!Files.isRegularFile(entry) || !entry.toString().endsWith(".jar")) {
        log.info("Sleipnir: AOT cache ineligible: directory on worker classpath ({})", entry);
        return Optional.empty();
      }
    }
    try {
      MessageDigest digest = Sha256.sha256Digest();
      update(digest, javaVersionString());
      update(digest, System.getProperty("os.name", ""));
      update(digest, System.getProperty("os.arch", ""));
      for (Path entry : entries) {
        update(digest, entry.toAbsolutePath().toString());
        update(digest, Long.toString(Files.size(entry)));
        update(digest, Sha256.sha256Hex(entry));
      }
      for (String flag : AgentMain.stableWorkerFlags()) {
        update(digest, flag);
      }
      return Optional.of(HexFormat.of().formatHex(digest.digest()));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to compute Sleipnir cache key", e);
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0); // field separator -- prevents "ab"+"c" colliding with "a"+"bc"
  }

  /**
   * Parses the classpath entries out of {@code commandTail}'s own {@code -cp}/{@code -classpath}/
   * {@code --class-path} argument, in order. Empty when none of those flags is present.
   */
  private static Optional<List<Path>> classpathEntries(List<String> commandTail) {
    for (int i = 0; i < commandTail.size() - 1; i++) {
      String arg = commandTail.get(i);
      if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
        List<Path> entries = new ArrayList<>();
        for (String entry : commandTail.get(i + 1).split(File.pathSeparator, -1)) {
          if (!entry.isBlank()) {
            entries.add(Path.of(entry));
          }
        }
        return Optional.of(entries);
      }
    }
    return Optional.empty();
  }

  /** {@code <javaExecutable> -version}'s own banner -- printed to stderr by every OpenJDK build. */
  private String javaVersionString() throws IOException {
    try {
      Process process =
          new ProcessBuilder(javaExecutable, "-version").redirectErrorStream(true).start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      process.waitFor(10, TimeUnit.SECONDS);
      return output.strip();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while capturing " + javaExecutable + " -version output", e);
    }
  }
}
