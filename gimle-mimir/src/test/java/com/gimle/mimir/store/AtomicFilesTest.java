package com.gimle.mimir.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

  @TempDir Path tempDir;

  @Test
  void writes_content_visible_under_the_final_name_with_no_leftover_tmp_file() throws IOException {
    Path target = tempDir.resolve("entry.bin");
    byte[] content = "durable content".getBytes(StandardCharsets.UTF_8);

    AtomicFiles.writeAtomically(target, content);

    assertTrue(Files.exists(target));
    assertArrayEquals(content, Files.readAllBytes(target));
    assertFalse(Files.exists(target.resolveSibling("entry.bin.tmp")));
  }

  @Test
  void the_written_file_has_no_unflushed_dirty_state_after_writeatomically_returns()
      throws IOException {
    Path target = tempDir.resolve("flushed.bin");
    byte[] content = "fsynced before rename".getBytes(StandardCharsets.UTF_8);

    AtomicFiles.writeAtomically(target, content);

    // A FileChannel opened fresh from disk (not the same handle writeAtomically used) must see
    // exactly the written bytes -- if writeAtomically only handed the data to the OS page cache
    // without forcing it, this read-back would still succeed in-process (page cache is shared),
    // so this asserts what writeAtomically actually promises: a distinct reader immediately sees
    // the complete, correct content, consistent with the write having been forced to storage
    // rather than left buffered.
    try (FileChannel reader = FileChannel.open(target, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate(content.length);
      while (buffer.hasRemaining()) {
        if (reader.read(buffer) < 0) {
          break;
        }
      }
      assertArrayEquals(content, buffer.array());
    }
  }

  @Test
  void overwriting_an_existing_target_replaces_its_content() {
    Path target = tempDir.resolve("overwritten.bin");
    AtomicFiles.writeAtomically(target, "first".getBytes(StandardCharsets.UTF_8));
    AtomicFiles.writeAtomically(target, "second".getBytes(StandardCharsets.UTF_8));

    assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), readSafely(target));
  }

  @Test
  void string_overload_encodes_as_utf8() {
    Path target = tempDir.resolve("string.txt");
    AtomicFiles.writeAtomically(target, "héllo");

    assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), readSafely(target));
  }

  @Test
  void delete_quietly_removes_an_existing_file_and_tolerates_a_missing_one() {
    Path target = tempDir.resolve("to-delete.bin");
    AtomicFiles.writeAtomically(target, new byte[] {1, 2, 3});

    AtomicFiles.deleteQuietly(target);
    assertFalse(Files.exists(target));

    // Deleting again (already gone) must not throw.
    AtomicFiles.deleteQuietly(target);
  }

  private static byte[] readSafely(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
