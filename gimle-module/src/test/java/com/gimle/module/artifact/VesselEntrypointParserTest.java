package com.gimle.module.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.vessel.VesselEntrypoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VesselEntrypointParserTest {

  @TempDir Path bundleRoot;

  private VesselEntrypoint parseBundle(String yaml) throws IOException {
    Files.writeString(bundleRoot.resolve(VesselEntrypoint.FILE_NAME), yaml);
    return VesselEntrypointParser.parseFromBundleRoot(bundleRoot);
  }

  @Test
  void a_command_and_workdir_parse_into_the_record() throws IOException {
    VesselEntrypoint entrypoint =
        parseBundle(
            """
            command: [java, -jar, quarkus-run.jar]
            workdir: app
            """);
    assertEquals(List.of("java", "-jar", "quarkus-run.jar"), entrypoint.command());
    assertEquals("app", entrypoint.workdir());
  }

  @Test
  void an_omitted_workdir_defaults_to_the_bundle_root() throws IOException {
    VesselEntrypoint entrypoint = parseBundle("command: [java, -jar, app.jar]\n");
    assertEquals(VesselEntrypoint.DEFAULT_WORKDIR, entrypoint.workdir());
  }

  @Test
  void a_missing_entrypoint_file_is_rejected_naming_the_bundle_root() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () -> VesselEntrypointParser.parseFromBundleRoot(bundleRoot));
    assertTrue(failure.getMessage().contains(VesselEntrypoint.FILE_NAME));
  }

  @Test
  void a_missing_command_is_rejected() {
    assertThrows(GimleManifestException.class, () -> parseBundle("workdir: app\n"));
  }

  @Test
  void a_non_list_command_is_rejected() {
    assertThrows(GimleManifestException.class, () -> parseBundle("command: java -jar app.jar\n"));
  }

  @Test
  void a_blank_command_entry_is_rejected() {
    assertThrows(GimleManifestException.class, () -> parseBundle("command: [java, '  ']\n"));
  }

  @Test
  void an_escaping_workdir_is_rejected() {
    assertThrows(
        GimleManifestException.class, () -> parseBundle("command: [run]\nworkdir: ../outside\n"));
  }

  @Test
  void an_absolute_workdir_is_rejected() {
    assertThrows(
        GimleManifestException.class, () -> parseBundle("command: [run]\nworkdir: /etc\n"));
  }

  @Test
  void malformed_yaml_is_rejected_as_a_manifest_exception() {
    assertThrows(GimleManifestException.class, () -> parseBundle("command: [unclosed\n"));
  }

  @Test
  void a_non_mapping_root_is_rejected() {
    assertThrows(GimleManifestException.class, () -> parseBundle("- just\n- a list\n"));
  }
}
