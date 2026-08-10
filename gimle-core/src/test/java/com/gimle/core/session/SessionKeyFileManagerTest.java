package com.gimle.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class SessionKeyFileManagerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void generates_a_key_on_first_run_and_reuses_it_on_later_runs() {
    Path keyFile = tempDir.resolve("session.key");

    SecretKey first = SessionKeyFileManager.loadOrCreate(keyFile);
    SecretKey second = SessionKeyFileManager.loadOrCreate(keyFile);

    assertArrayEquals(first.getEncoded(), second.getEncoded());
  }

  @Test
  void a_corrupted_length_key_file_is_rejected_with_a_clear_error() throws Exception {
    Path keyFile = tempDir.resolve("corrupted.key");
    Files.write(keyFile, new byte[] {1, 2, 3, 4, 5, 6, 7});

    assertThrows(GimleSecretsException.class, () -> SessionKeyFileManager.loadOrCreate(keyFile));
  }

  @Test
  void an_empty_key_file_is_rejected_with_a_clear_error() throws Exception {
    Path keyFile = tempDir.resolve("empty.key");
    Files.write(keyFile, new byte[0]);

    assertThrows(GimleSecretsException.class, () -> SessionKeyFileManager.loadOrCreate(keyFile));
  }
}
