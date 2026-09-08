package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreMojo#buildCommand()} needs a live Maven session to resolve the runtime classpath at
 * all, but the actual command line it hands to the spawned process is a pure function of its own
 * inputs, split out into the static {@link StoreMojo#buildCommand(String, String, String, String,
 * String, String, String, String, String, String, String)} overload specifically so it can be
 * asserted here without any of that machinery -- the same seam {@link InitMojo} establishes for its
 * own {@code buildCommand}.
 */
class StoreMojoTest {

  @Test
  void forwards_tls_cert_key_and_ca_files_as_plain_gimle_tls_system_properties() {
    List<String> command =
        StoreMojo.buildCommand(
            "java",
            "store.jar",
            "state-dir",
            "9080",
            "9091",
            null,
            null,
            "tls",
            "/tls/store.crt",
            "/tls/store.key",
            "/tls/ca.crt");

    assertTrue(command.contains("-Dgimle.tls.certFile=/tls/store.crt"));
    assertTrue(command.contains("-Dgimle.tls.keyFile=/tls/store.key"));
    assertTrue(command.contains("-Dgimle.tls.caFile=/tls/ca.crt"));
  }

  @Test
  void unset_tls_files_leave_the_corresponding_flags_off_entirely() {
    List<String> command =
        StoreMojo.buildCommand(
            "java", "store.jar", "state-dir", "9080", "9091", null, null, null, null, null, null);

    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.")));
  }

  @Test
  void blank_tls_files_are_treated_as_unset() {
    List<String> command =
        StoreMojo.buildCommand(
            "java",
            "store.jar",
            "state-dir",
            "9080",
            "9091",
            null,
            null,
            "tls",
            "   ",
            "   ",
            "   ");

    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.cert")));
    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.key")));
    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.ca")));
  }

  @Test
  void still_threads_peers_and_csr_endpoint_alongside_tls_flags() {
    List<String> command =
        StoreMojo.buildCommand(
            "java",
            "store.jar",
            "state-dir",
            "9080",
            "9091",
            "127.0.0.1:9080",
            "127.0.0.1:8080",
            "tls",
            "/tls/store.crt",
            "/tls/store.key",
            "/tls/ca.crt");

    int peersIndex = command.indexOf("--peers");
    assertTrue(peersIndex >= 0);
    int csrIndex = command.indexOf("--csr-endpoint");
    assertTrue(csrIndex >= 0);
  }
}
