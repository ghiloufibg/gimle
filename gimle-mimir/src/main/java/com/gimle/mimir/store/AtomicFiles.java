package com.gimle.mimir.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Temp-file-then-atomic-move write helper, extracted from {@link StateStore} so {@code raft}'s
 * {@code RaftLog} (which persists binary log entries, not just YAML strings) can reuse the exact
 * same durability idiom rather than duplicating it -- one write-durability mechanism for the whole
 * control plane.
 */
public final class AtomicFiles {

  private AtomicFiles() {}

  public static void writeAtomically(Path target, byte[] content) {
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path targetFileName = target.getFileName();
      if (targetFileName == null) {
        throw new IllegalArgumentException("target must have a file name: " + target);
      }
      Path tmp = target.resolveSibling(targetFileName.toString() + ".tmp");
      // fsync the tmp file's own content before it's ever visible under its final name: Raft
      // (and every StateStore mutation) treats this call's return as "durable", so the bytes
      // must actually be on stable storage, not just handed to the OS page cache, before that
      // promise is kept -- otherwise a crash between this write and a page-cache flush silently
      // loses data the caller already relied on being persisted.
      try (FileChannel channel =
          FileChannel.open(
              tmp,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(content));
        channel.force(true);
      }
      try {
        Files.move(
            tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      if (parent != null) {
        fsyncDirectoryBestEffort(parent);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * POSIX filesystems durably commit a rename only once the containing directory entry itself is
   * synced -- fsync-ing the file alone is not enough to guarantee the rename survives a crash.
   * Windows/NTFS has no equivalent "open a directory as a file" operation, so this is best-effort:
   * a platform that rejects it already commits directory-entry changes through other means, and
   * this project stays portable by never branching on OS here, only on whether the open succeeds.
   */
  private static void fsyncDirectoryBestEffort(Path directory) {
    try (FileChannel dirChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
      dirChannel.force(true);
    } catch (IOException e) {
      // Not supported on this platform/filesystem (e.g. Windows) -- the file-level fsync above
      // already covers the content; only the directory-entry durability edge case is skipped.
    }
  }

  public static void writeAtomically(Path target, String content) {
    writeAtomically(target, content.getBytes(StandardCharsets.UTF_8));
  }

  public static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
