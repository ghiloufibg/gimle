package com.gimle.mimir.raft;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.store.AtomicFiles;
import com.gimle.mimir.store.StateSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A Raft node's persisted log plus current term/vote and its latest installed snapshot, under a
 * {@code raft/} directory. The log itself is the node's durable source of truth -- the state
 * machine ({@code StateStore}) holds nothing on disk of its own, so recovery is this log's snapshot
 * plus committed-entry replay -- and rides a {@link WriteAheadLog}: append-only segment files, one
 * fsync per appended record, never a rewrite in place. Term and vote are kept in their own single
 * file (written through {@link AtomicFiles}, whole-file-replace being the right idiom for a
 * two-field record that must never be observed torn), as are the compaction floor and the snapshot
 * bytes it replaced everything up to.
 *
 * <p>Not thread-safe on its own -- callers ({@link RaftNode}) are expected to serialize access
 * under their own lock; it's simpler to require the single caller already holds a lock for Raft's
 * own safety mechanics.
 */
public final class RaftLog implements AutoCloseable {

  private final Path root;
  private final WriteAheadLog wal;
  private final NavigableMap<Long, LogEntry> entries = new TreeMap<>();
  private long currentTerm;
  private String votedFor;
  private long snapshotLastIncludedIndex;
  private long snapshotLastIncludedTerm;

  public RaftLog(Path root) {
    this.root = root;
    try {
      Files.createDirectories(snapshotDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    loadState();
    loadSnapshotMeta();
    this.wal = new WriteAheadLog(walDir());
    wal.replay(
        new WriteAheadLog.RecordReplay() {
          @Override
          public void onEntry(LogEntry entry) {
            // A re-append after truncation supersedes everything from its index up -- the same
            // rule a live conflicting-entry truncation applies, reproduced in replay order.
            entries.tailMap(entry.index(), true).clear();
            entries.put(entry.index(), entry);
          }

          @Override
          public void onTruncate(long fromIndex) {
            entries.tailMap(fromIndex, true).clear();
          }
        });
    // Compaction deletes only whole segments, so retained segments can still open with records
    // already covered by the snapshot floor -- the snapshot supersedes those.
    entries.headMap(snapshotLastIncludedIndex, true).clear();
  }

  @Override
  public void close() {
    wal.close();
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
    wal.appendEntry(entry);
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
   * below it, since that entry's own file no longer exists once compacted.
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
   * Discards every entry at or after {@code index} -- truncation of conflicting entries once a
   * follower's log is found to diverge from the leader's, or of a leader's own timed-out proposal.
   * Durable via an explicit truncate record rather than rewriting segments: replay applies the
   * record in order, so the discarded entries stay discarded even if nothing is ever appended over
   * them again.
   */
  public void truncateFrom(long index) {
    if (index > lastIndex()) {
      return;
    }
    wal.appendTruncate(index, lastIndex());
    entries.tailMap(index, true).clear();
  }

  // ---- snapshot ----

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
    entries.headMap(lastIncludedIndex, true).clear();
    wal.compact(lastIncludedIndex);
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

  private Path walDir() {
    return root.resolve("wal");
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

  private static Map<?, ?> loadYamlMap(Path file) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw;
    try {
      raw = yaml.load(new ByteArrayInputStream(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      throw new IllegalStateException("corrupt Raft log file " + file, e);
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalStateException("expected a YAML mapping in " + file);
    }
    return map;
  }
}
