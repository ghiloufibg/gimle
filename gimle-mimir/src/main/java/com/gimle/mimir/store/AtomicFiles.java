package com.gimle.mimir.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
      Files.write(tmp, content);
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
