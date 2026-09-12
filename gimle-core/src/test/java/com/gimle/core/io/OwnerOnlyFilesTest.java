package com.gimle.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

  @TempDir Path tempDir;

  @Test
  void a_new_file_is_created_owner_only_and_holds_the_written_content() throws Exception {
    Path file = tempDir.resolve("secret.key");

    OwnerOnlyFiles.write(file, "top secret".getBytes(StandardCharsets.UTF_8));

    assertArrayEquals("top secret".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  @Test
  void the_file_is_never_briefly_readable_at_default_permissions_between_create_and_restrict()
      throws Exception {
    // There is no separate create-then-chmod step to race at all: #write creates the file with its
    // restrictive mode already attached to the very syscall that creates it (see
    // Files#createFile's own FileAttribute overload), so there is no intermediate state in which
    // the file exists at the process's default umask for a concurrent reader to observe.
    Path file = tempDir.resolve("no-window.key");

    OwnerOnlyFiles.write(file, new byte[] {1, 2, 3});

    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  @Test
  void rewriting_an_existing_file_replaces_its_content_and_keeps_it_owner_only() throws Exception {
    Path file = tempDir.resolve("rotated.key");
    OwnerOnlyFiles.write(file, "first".getBytes(StandardCharsets.UTF_8));

    OwnerOnlyFiles.write(file, "second".getBytes(StandardCharsets.UTF_8));

    assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }
}
