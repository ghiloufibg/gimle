package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DeployMojo#buildCommand()} needs a live Maven session to resolve the runtime classpath at
 * all, but the actual command line it hands to the spawned {@code GimleCli} process is a pure
 * function of its own inputs, split out into the static {@link DeployMojo#buildCommand(String,
 * String, String, String, String, String, String, String)} overload specifically so it can be
 * asserted here without any of that machinery -- the same seam {@link InitMojo} establishes for its
 * own {@code buildCommand}.
 */
class DeployMojoTest {

  @Test
  void forwards_tls_cert_key_and_ca_files_as_plain_gimle_tls_system_properties() {
    List<String> command =
        DeployMojo.buildCommand(
            "java",
            "cli.jar",
            "deployment.yaml",
            "127.0.0.1:8080",
            "tls",
            "/tls/operator.crt",
            "/tls/operator.key",
            "/tls/ca.crt");

    assertTrue(command.contains("-Dgimle.transport.protocol=tls"));
    assertTrue(command.contains("-Dgimle.tls.certFile=/tls/operator.crt"));
    assertTrue(command.contains("-Dgimle.tls.keyFile=/tls/operator.key"));
    assertTrue(command.contains("-Dgimle.tls.caFile=/tls/ca.crt"));
  }

  @Test
  void unset_transport_protocol_and_tls_files_leave_every_tls_flag_off() {
    List<String> command =
        DeployMojo.buildCommand(
            "java", "cli.jar", "deployment.yaml", "127.0.0.1:8080", null, null, null, null);

    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.transport.protocol")));
    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.")));
  }

  @Test
  void blank_tls_files_are_treated_as_unset() {
    List<String> command =
        DeployMojo.buildCommand(
            "java", "cli.jar", "deployment.yaml", "127.0.0.1:8080", "tls", "  ", "  ", "  ");

    assertTrue(command.contains("-Dgimle.transport.protocol=tls"));
    assertFalse(command.stream().anyMatch(arg -> arg.startsWith("-Dgimle.tls.")));
  }

  @Test
  void still_threads_the_manifest_file_and_server_through_to_the_apply_command() {
    List<String> command =
        DeployMojo.buildCommand(
            "java", "cli.jar", "deployment.yaml", "127.0.0.1:8080", null, null, null, null);

    int fileIndex = command.indexOf("-f");
    assertTrue(fileIndex >= 0);
    assertTrue(command.get(fileIndex + 1).equals("deployment.yaml"));
    int serverIndex = command.indexOf("--server");
    assertTrue(serverIndex >= 0);
    assertTrue(command.get(serverIndex + 1).equals("127.0.0.1:8080"));
  }
}
