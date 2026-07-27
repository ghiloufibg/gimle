package com.gimle.controlplane.raft;

import com.gimle.controlplane.store.AtomicFiles;
import com.gimle.controlplane.store.StateSnapshot;
import com.gimle.core.exception.GimleRaftException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A Raft node's persisted log plus current term/vote and its latest installed snapshot (design
 * §2.5): every entry survives a crash before it's ever acknowledged, in a {@code raft/} directory
 * sibling to {@code StateStore}'s own {@code deployments/}/{@code assignments/}/{@code nodes/},
 * written through {@link AtomicFiles} -- the same durability idiom, not a second one. Term/vote is
 * kept in one file (design §2.5: "must never be observed torn relative to each other"); each log
 * entry is its own immutable file, {@link RaftCodec}-encoded; a compaction floor and the snapshot
 * bytes it replaced everything up to are kept alongside.
 *
 * <p>Not thread-safe on its own -- callers ({@link RaftNode}) are expected to serialize access
 * under their own lock, matching how {@code StateStore} itself relies on its maps' own concurrency
 * rather than external synchronization for a different reason (many independent keys); here it's
 * simpler to require the single caller already holds a lock for Raft's own safety mechanics.
 */
public final class RaftLog {

  private final Path root;
  private final NavigableMap<Long, LogEntry> entries = new TreeMap<>();
  private long currentTerm;
  private String votedFor;
  private long snapshotLastIncludedIndex;
  private long snapshotLastIncludedTerm;

  public RaftLog(Path root) {
    this.root = root;
    try {
      Files.createDirectories(logDir());
      Files.createDirectories(snapshotDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    loadState();
    loadSnapshotMeta();
    loadEntries();
  }

  // ---- term / vote ----

  public long currentTerm() {
    return currentTerm;
  }

  public Optional<String> votedFor() {
    return Optional.ofNullable(votedFor);
  }

  public void setTermAndVote(long term, Optional<String> votedForCandidate) {
    this.currentTerm = term;
    this.votedFor = votedForCandidate.orElse(null);
    writeState();
  }

  // ---- log ----

  public void append(LogEntry entry) {
    AtomicFiles.writeAtomically(entryFile(entry.index()), RaftCodec.encodeLogEntry(entry));
    entries.put(entry.index(), entry);
  }

  public Optional<LogEntry> get(long index) {
    return Optional.ofNullable(entries.get(index));
  }

  public List<LogEntry> entriesFrom(long index) {
    return List.copyOf(entries.tailMap(index, true).values());
  }

  public long lastIndex() {
    return entries.isEmpty() ? snapshotLastIncludedIndex : entries.lastKey();
  }

  public long lastTerm() {
    return entries.isEmpty() ? snapshotLastIncludedTerm : entries.lastEntry().getValue().term();
  }

  /**
   * The term of the entry at {@code index} -- consulting the snapshot floor for an index at or
   * below it (design §2.3/§2.4), since that entry's own file no longer exists once compacted.
   */
  public long termAt(long index) {
    if (index == 0) {
      return 0;
    }
    if (index == snapshotLastIncludedIndex) {
      return snapshotLastIncludedTerm;
    }
    LogEntry entry = entries.get(index);
    if (entry == null) {
      throw new IllegalArgumentException(
          "no log entry at index " + index + " (compacted or never existed)");
    }
    return entry.term();
  }

  /**
   * Deletes every entry at or after {@code index} -- conflicting-entry truncation (design §2.3).
   */
  public void truncateFrom(long index) {
    for (Long key : new ArrayList<>(entries.tailMap(index, true).keySet())) {
      AtomicFiles.deleteQuietly(entryFile(key));
      entries.remove(key);
    }
  }

  // ---- snapshot (design §2.4) ----

  /**
   * Persists {@code snapshotBytes} as this node's new compaction point and discards every log entry
   * at or below {@code lastIncludedIndex} -- used both for this node's own local compaction
   * (leader, once the log exceeds the snapshot threshold) and when installing a snapshot received
   * from a peer (follower, via {@code InstallSnapshot}).
   */
  public void installSnapshot(long lastIncludedIndex, long lastIncludedTerm, byte[] snapshotBytes) {
    AtomicFiles.writeAtomically(snapshotStateFile(), snapshotBytes);
    writeSnapshotMeta(lastIncludedIndex, lastIncludedTerm);
    this.snapshotLastIncludedIndex = lastIncludedIndex;
    this.snapshotLastIncludedTerm = lastIncludedTerm;
    for (Long key : new ArrayList<>(entries.headMap(lastIncludedIndex, true).keySet())) {
      AtomicFiles.deleteQuietly(entryFile(key));
      entries.remove(key);
    }
  }

  public long snapshotLastIncludedIndex() {
    return snapshotLastIncludedIndex;
  }

  public long snapshotLastIncludedTerm() {
    return snapshotLastIncludedTerm;
  }

  /**
   * The most recently installed snapshot's decoded contents, if this node has ever installed one.
   */
  public Optional<StateSnapshot> loadSnapshot() {
    Optional<byte[]> bytes = snapshotBytes();
    if (bytes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(RaftCodec.decodeSnapshot(bytes.get()));
    } catch (RuntimeException e) {
      throw GimleRaftException.snapshotCorrupted(snapshotStateFile(), e);
    }
  }

  /**
   * The raw bytes of the most recently installed snapshot, if any -- what a leader resends verbatim
   * to a peer that needs {@code InstallSnapshot}, without decoding and re-encoding it.
   */
  public Optional<byte[]> snapshotBytes() {
    Path file = snapshotStateFile();
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Optional.of(bytes);
  }

  // ---- disk layout ----

  private Path stateFile() {
    return root.resolve("state.yaml");
  }

  private Path logDir() {
    return root.resolve("log");
  }

  private Path entryFile(long index) {
    return logDir().resolve(index + ".bin");
  }

  private Path snapshotDir() {
    return root.resolve("snapshot");
  }

  private Path snapshotMetaFile() {
    return snapshotDir().resolve("meta.yaml");
  }

  private Path snapshotStateFile() {
    return snapshotDir().resolve("state.bin");
  }

  private void loadState() {
    if (!Files.exists(stateFile())) {
      this.currentTerm = 0;
      this.votedFor = null;
      return;
    }
    Map<?, ?> map = loadYamlMap(stateFile());
    this.currentTerm = ((Number) map.get("currentTerm")).longValue();
    this.votedFor = (String) map.get("votedFor");
  }

  private void writeState() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("currentTerm", currentTerm);
    map.put("votedFor", votedFor);
    AtomicFiles.writeAtomically(stateFile(), new Yaml().dump(map));
  }

  private void loadSnapshotMeta() {
    if (!Files.exists(snapshotMetaFile())) {
      this.snapshotLastIncludedIndex = 0;
      this.snapshotLastIncludedTerm = 0;
      return;
    }
    Map<?, ?> map = loadYamlMap(snapshotMetaFile());
    this.snapshotLastIncludedIndex = ((Number) map.get("lastIncludedIndex")).longValue();
    this.snapshotLastIncludedTerm = ((Number) map.get("lastIncludedTerm")).longValue();
  }

  private void writeSnapshotMeta(long lastIncludedIndex, long lastIncludedTerm) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("lastIncludedIndex", lastIncludedIndex);
    map.put("lastIncludedTerm", lastIncludedTerm);
    AtomicFiles.writeAtomically(snapshotMetaFile(), new Yaml().dump(map));
  }

  private void loadEntries() {
    if (!Files.isDirectory(logDir())) {
      return;
    }
    try (var stream = Files.newDirectoryStream(logDir(), "*.bin")) {
      for (Path file : stream) {
        byte[] bytes;
        try {
          bytes = Files.readAllBytes(file);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        LogEntry entry;
        try {
          entry = RaftCodec.decodeLogEntry(bytes);
        } catch (RuntimeException e) {
          throw new IllegalStateException("corrupt Raft log entry file " + file, e);
        }
        entries.put(entry.index(), entry);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<?, ?> loadYamlMap(Path file) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw;
    try {
      raw = yaml.load(new ByteArrayInputStream(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalStateException("expected a YAML mapping in " + file);
    }
    return map;
  }
}
