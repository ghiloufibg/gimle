package com.gimle.mimir.raft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The append-only segment-file write-ahead log behind {@link RaftLog}: every record is appended to
 * the newest segment file and fsynced before the caller is answered, and nothing in an existing
 * segment is ever rewritten in place -- durability comes from the append-plus-fsync itself, not
 * from the temp-file-then-atomic-rename idiom the rest of the store uses for whole-file replaces.
 *
 * <p>Two record kinds ride the log. An {@code ENTRY} record carries one encoded {@link LogEntry}; a
 * {@code TRUNCATE} record marks that every index at or above its {@code fromIndex} was discarded (a
 * follower found them conflicting with its leader's log, or a leader gave up on a timed-out
 * proposal). Replay applies both in file order: an entry at index {@code i} first drops every
 * in-memory entry at {@code >= i} (a re-append after truncation supersedes what it replaced), and a
 * truncate record drops {@code >= fromIndex} outright. The explicit truncate record is what keeps a
 * truncation durable when nothing is ever re-appended over the hole -- without it, a crash after a
 * timed-out proposal's truncation would resurrect on replay the very entry the caller was already
 * told had failed, exactly the ghost write the truncation existed to prevent.
 *
 * <p>Each record is {@code [length][crc32][payload]}. A crash mid-append can only tear the tail of
 * the newest segment: on open, a short or checksum-failing record there is discarded by truncating
 * the file back to the last intact record, while the same damage anywhere else is genuine
 * corruption and refuses to load. Compaction (after a snapshot) deletes whole closed segments whose
 * every record is covered by the snapshot floor -- covered meaning the highest index the record
 * touched (an entry's own index; the pre-truncation last index for a truncate record, since
 * deleting the truncate must never outlive the entries it erased) is at or below the floor.
 */
final class WriteAheadLog implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

  private static final byte RECORD_ENTRY = 1;
  private static final byte RECORD_TRUNCATE = 2;
  private static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES;
  private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
  private static final long SEGMENT_MAX_BYTES = 4L * 1024 * 1024;

  /** One closed or active segment file, ordered by sequence number. */
  private record Segment(long sequence, Path file) {}

  private final Path dir;
  private final List<Segment> segments = new ArrayList<>();
  private final Map<Long, Long> maxAffectedIndexBySegment = new HashMap<>();
  private FileChannel activeChannel;

  WriteAheadLog(Path dir) {
    this.dir = dir;
    try {
      Files.createDirectories(dir);
      try (var stream = Files.newDirectoryStream(dir, "*.wal")) {
        for (Path file : stream) {
          segments.add(new Segment(sequenceOf(file), file));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    segments.sort(Comparator.comparingLong(Segment::sequence));
  }

  /** Replays every intact record, oldest segment first, into {@code replay}. */
  void replay(RecordReplay replay) {
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      boolean last = i == segments.size() - 1;
      long validBytes = replaySegment(segment, last, replay);
      if (last && validBytes >= 0) {
        discardTornTail(segment, validBytes);
      }
    }
    openActiveChannel();
  }

  interface RecordReplay {
    void onEntry(LogEntry entry);

    void onTruncate(long fromIndex);
  }

  void appendEntry(LogEntry entry) {
    byte[] encoded = RaftCodec.encodeLogEntry(entry);
    ByteBuffer payload = ByteBuffer.allocate(1 + encoded.length);
    payload.put(RECORD_ENTRY).put(encoded).flip();
    appendRecord(payload, entry.index());
  }

  void appendTruncate(long fromIndex, long priorLastIndex) {
    ByteBuffer payload = ByteBuffer.allocate(1 + Long.BYTES + Long.BYTES);
    payload.put(RECORD_TRUNCATE).putLong(fromIndex).putLong(priorLastIndex).flip();
    appendRecord(payload, priorLastIndex);
  }

  /**
   * Deletes every closed segment fully covered by {@code snapshotFloor} -- the active segment is
   * never deleted, even when fully covered, so the open append channel stays valid.
   */
  void compact(long snapshotFloor) {
    var it = segments.iterator();
    while (it.hasNext()) {
      Segment segment = it.next();
      if (segments.size() == 1 || segment == segments.getLast()) {
        return;
      }
      long maxAffected = maxAffectedIndexBySegment.getOrDefault(segment.sequence(), Long.MAX_VALUE);
      if (maxAffected > snapshotFloor) {
        continue;
      }
      try {
        Files.deleteIfExists(segment.file());
      } catch (IOException e) {
        log.warn("failed to delete compacted WAL segment {}: {}", segment.file(), e.getMessage());
        continue;
      }
      maxAffectedIndexBySegment.remove(segment.sequence());
      it.remove();
    }
  }

  @Override
  public void close() {
    if (activeChannel != null) {
      try {
        activeChannel.close();
      } catch (IOException e) {
        log.warn("failed to close WAL segment channel: {}", e.getMessage());
      }
      activeChannel = null;
    }
  }

  private void appendRecord(ByteBuffer payload, long maxAffectedIndex) {
    try {
      rotateIfNeeded();
      CRC32 crc = new CRC32();
      crc.update(payload.duplicate());
      ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
      header.putInt(payload.remaining()).putInt((int) crc.getValue()).flip();
      activeChannel.write(new ByteBuffer[] {header, payload});
      activeChannel.force(true);
      long sequence = segments.getLast().sequence();
      maxAffectedIndexBySegment.merge(sequence, maxAffectedIndex, Math::max);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void rotateIfNeeded() throws IOException {
    if (activeChannel == null) {
      openActiveChannel();
    }
    if (activeChannel.size() < SEGMENT_MAX_BYTES) {
      return;
    }
    activeChannel.close();
    activeChannel = null;
    createSegment(segments.getLast().sequence() + 1);
  }

  private void openActiveChannel() {
    try {
      if (segments.isEmpty()) {
        createSegment(1);
        return;
      }
      activeChannel =
          FileChannel.open(
              segments.getLast().file(), StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void createSegment(long sequence) throws IOException {
    Path file = dir.resolve(String.format("%020d.wal", sequence));
    activeChannel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    segments.add(new Segment(sequence, file));
    // A new directory entry must itself be synced for the segment to survive a crash -- same
    // POSIX rule AtomicFiles handles for renames; best-effort for the same portability reason.
    try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
      dirChannel.force(true);
    } catch (IOException e) {
      // Not supported on this platform/filesystem (e.g. Windows); the record-level fsyncs still
      // cover the content itself.
    }
  }

  /**
   * Replays one segment, returning {@code -1} if every byte parsed cleanly, or the offset of the
   * torn final record when the damage is the newest segment's crash-torn tail; throws for damage
   * anywhere else. The distinction rests on the single-writer append discipline: a crash can only
   * ever tear the file's very last record, so a {@link #CORRUPT_RECORD} (bytes fully present but
   * wrong, intact data following) is damage no crash produced, and silently discarding from it
   * onward would drop acknowledged entries -- the log refuses to load instead.
   */
  private long replaySegment(Segment segment, boolean tornTailTolerated, RecordReplay replay) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(segment.file());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long segmentMaxAffected = 0;
    while (buffer.remaining() > 0) {
      int recordStart = buffer.position();
      long recordMaxAffected = tryReplayOneRecord(buffer, replay);
      if (recordMaxAffected < 0) {
        if (recordMaxAffected == CORRUPT_RECORD || !tornTailTolerated) {
          throw new IllegalStateException(
              "corrupt Raft WAL record at offset " + recordStart + " in " + segment.file());
        }
        if (segmentMaxAffected > 0) {
          maxAffectedIndexBySegment.merge(segment.sequence(), segmentMaxAffected, Math::max);
        }
        return recordStart;
      }
      segmentMaxAffected = Math.max(segmentMaxAffected, recordMaxAffected);
    }
    if (segmentMaxAffected > 0) {
      maxAffectedIndexBySegment.merge(segment.sequence(), segmentMaxAffected, Math::max);
    }
    return -1;
  }

  private static final long TORN_RECORD = -1;
  private static final long CORRUPT_RECORD = -2;

  /**
   * Returns the record's highest touched index (always positive), {@link #TORN_RECORD} for a record
   * whose claimed extent runs past the end of the file, or {@link #CORRUPT_RECORD} for one whose
   * bytes are all present but wrong (see {@link #replaySegment} for why the two are treated so
   * differently).
   */
  private static long tryReplayOneRecord(ByteBuffer buffer, RecordReplay replay) {
    if (buffer.remaining() < HEADER_BYTES) {
      return TORN_RECORD;
    }
    int length = buffer.getInt();
    int expectedCrc = buffer.getInt();
    if (length <= 0 || length > MAX_RECORD_BYTES || buffer.remaining() < length) {
      // A garbage length field can't be told apart from a partially-written one, and either way
      // no later record boundary is recoverable past it -- torn-tail treatment for both.
      return TORN_RECORD;
    }
    boolean reachesEof = buffer.remaining() == length;
    ByteBuffer payload = buffer.slice(buffer.position(), length);
    CRC32 crc = new CRC32();
    crc.update(payload.duplicate());
    if ((int) crc.getValue() != expectedCrc) {
      // A checksum failure on the file's final record can be an out-of-order page flush from the
      // crash that tore it; one mid-file, with intact data after, cannot.
      return reachesEof ? TORN_RECORD : CORRUPT_RECORD;
    }
    buffer.position(buffer.position() + length);
    byte kind = payload.get();
    switch (kind) {
      case RECORD_ENTRY -> {
        byte[] encoded = new byte[payload.remaining()];
        payload.get(encoded);
        LogEntry entry;
        try {
          entry = RaftCodec.decodeLogEntry(encoded);
        } catch (RuntimeException e) {
          return CORRUPT_RECORD;
        }
        replay.onEntry(entry);
        return entry.index();
      }
      case RECORD_TRUNCATE -> {
        if (payload.remaining() != Long.BYTES + Long.BYTES) {
          return CORRUPT_RECORD;
        }
        long fromIndex = payload.getLong();
        long priorLastIndex = payload.getLong();
        replay.onTruncate(fromIndex);
        return priorLastIndex;
      }
      default -> {
        return CORRUPT_RECORD;
      }
    }
  }

  private void discardTornTail(Segment segment, long validBytes) {
    try (FileChannel channel = FileChannel.open(segment.file(), StandardOpenOption.WRITE)) {
      if (channel.size() > validBytes) {
        log.warn(
            "discarding torn tail of WAL segment {} ({} of {} bytes intact)",
            segment.file(),
            validBytes,
            channel.size());
        channel.truncate(validBytes);
        channel.force(true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long sequenceOf(Path file) {
    String name = file.getFileName().toString();
    try {
      return Long.parseLong(name.substring(0, name.length() - ".wal".length()));
    } catch (NumberFormatException e) {
      throw new IllegalStateException("malformed WAL segment file name: " + file, e);
    }
  }
}
