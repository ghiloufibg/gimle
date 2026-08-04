package com.gimle.controlplane.authz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Account;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapAccountFileTest {

  @TempDir Path tempDir;

  private static final String PROPERTY = "gimle.bootstrap.accountFile";

  @AfterEach
  void clearProperty() {
    System.clearProperty(PROPERTY);
  }

  @Test
  void returns_empty_when_the_property_is_not_set() {
    assertEquals(Optional.empty(), BootstrapAccountFile.loadIfConfigured());
  }

  @Test
  void returns_empty_when_the_property_points_at_a_missing_file() {
    System.setProperty(PROPERTY, tempDir.resolve("does-not-exist.yaml").toString());
    assertEquals(Optional.empty(), BootstrapAccountFile.loadIfConfigured());
  }

  @Test
  void loads_username_and_password_hash_from_a_configured_file() throws IOException {
    byte[] hash = new byte[] {1, 2, 3, 4, 5};
    Path file = tempDir.resolve("bootstrap-account.yaml");
    Files.writeString(
        file, "username: admin\npasswordHash: " + Base64.getEncoder().encodeToString(hash) + "\n");
    System.setProperty(PROPERTY, file.toString());

    Optional<Account> loaded = BootstrapAccountFile.loadIfConfigured();

    assertTrue(loaded.isPresent());
    assertEquals("admin", loaded.get().username());
    assertArrayEquals(hash, loaded.get().passwordHash());
  }
}
