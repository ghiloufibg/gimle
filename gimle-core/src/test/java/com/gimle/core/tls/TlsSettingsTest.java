package com.gimle.core.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleTlsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TlsSettingsTest {

  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir private Path tempDir;

  @AfterEach
  void clearProperties() {
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  void constructor_rejects_a_cert_file_that_does_not_exist() throws IOException {
    Path missing = tempDir.resolve("missing-cert.pem");
    Path key = touch(tempDir.resolve("key.pem"));
    Path ca = touch(tempDir.resolve("ca.pem"));

    GimleTlsException e =
        assertThrows(GimleTlsException.class, () -> new TlsSettings(missing, key, ca));
    assertTrue(e.getMessage().contains(CERT_FILE_PROPERTY));
    assertTrue(e.getMessage().contains(missing.toString()));
  }

  @Test
  void constructor_rejects_a_missing_key_file() throws IOException {
    Path cert = touch(tempDir.resolve("cert.pem"));
    Path missingKey = tempDir.resolve("missing-key.pem");
    Path ca = touch(tempDir.resolve("ca.pem"));

    assertThrows(GimleTlsException.class, () -> new TlsSettings(cert, missingKey, ca));
  }

  @Test
  void constructor_rejects_a_missing_ca_file() throws IOException {
    Path cert = touch(tempDir.resolve("cert.pem"));
    Path key = touch(tempDir.resolve("key.pem"));
    Path missingCa = tempDir.resolve("missing-ca.pem");

    assertThrows(GimleTlsException.class, () -> new TlsSettings(cert, key, missingCa));
  }

  @Test
  void constructor_accepts_three_existing_files() throws IOException {
    Path cert = touch(tempDir.resolve("cert.pem"));
    Path key = touch(tempDir.resolve("key.pem"));
    Path ca = touch(tempDir.resolve("ca.pem"));

    TlsSettings settings = new TlsSettings(cert, key, ca);
    assertEquals(cert, settings.certFile());
    assertEquals(key, settings.keyFile());
    assertEquals(ca, settings.caFile());
  }

  @Test
  void from_config_fails_fast_when_a_property_is_entirely_unset() {
    System.setProperty(CERT_FILE_PROPERTY, tempDir.resolve("cert.pem").toString());
    // keyFile and caFile deliberately left unset.
    GimleTlsException e = assertThrows(GimleTlsException.class, TlsSettings::fromConfig);
    assertTrue(e.getMessage().contains(KEY_FILE_PROPERTY));
  }

  @Test
  void from_config_reads_all_three_properties() throws IOException {
    Path cert = touch(tempDir.resolve("cert.pem"));
    Path key = touch(tempDir.resolve("key.pem"));
    Path ca = touch(tempDir.resolve("ca.pem"));
    System.setProperty(CERT_FILE_PROPERTY, cert.toString());
    System.setProperty(KEY_FILE_PROPERTY, key.toString());
    System.setProperty(CA_FILE_PROPERTY, ca.toString());

    TlsSettings settings = TlsSettings.fromConfig();
    assertEquals(cert, settings.certFile());
    assertEquals(key, settings.keyFile());
    assertEquals(ca, settings.caFile());
  }

  private static Path touch(Path path) throws IOException {
    return Files.writeString(path, "placeholder");
  }
}
