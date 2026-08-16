package com.gimle.hilmir.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.Version;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayJarLocatorTest {

  @TempDir Path tempDir;

  private static ExtensionFlags flags(String... args) {
    return ExtensionFlags.parse(List.of(args), Set.of("--wait", "--dry-run"), Set.of("--set"));
  }

  @Test
  void modules_dir_flag_wins_even_when_gimle_home_is_also_set() {
    Path fromFlag = tempDir.resolve("from-flag");
    Path resolved =
        GatewayJarLocator.resolveModulesDir(
            flags("--modules-dir", fromFlag.toString()), "/from/env");
    assertEquals(fromFlag, resolved);
  }

  @Test
  void modules_dir_defaults_to_gimle_home_slash_modules_when_no_flag_is_given() {
    Path resolved = GatewayJarLocator.resolveModulesDir(flags(), "/opt/gimle-platform");
    assertEquals(Path.of("/opt/gimle-platform/modules"), resolved);
  }

  @Test
  void modules_dir_fails_clearly_when_neither_the_flag_nor_gimle_home_is_set() {
    HilmirException e =
        assertThrows(
            HilmirException.class, () -> GatewayJarLocator.resolveModulesDir(flags(), null));
    assertTrue(e.getMessage().contains("--modules-dir"));
    assertTrue(e.getMessage().contains("GIMLE_HOME"));
  }

  @Test
  void find_gateway_jar_fails_clearly_when_none_exists() {
    HilmirException e =
        assertThrows(HilmirException.class, () -> GatewayJarLocator.findGatewayJar(tempDir));
    assertTrue(e.getMessage().contains("gimle-gateway-"));
  }

  @Test
  void find_gateway_jar_fails_clearly_when_more_than_one_exists() throws Exception {
    Files.writeString(tempDir.resolve("gimle-gateway-1.0.0.jar"), "not a real jar");
    Files.writeString(tempDir.resolve("gimle-gateway-1.1.0.jar"), "not a real jar");

    HilmirException e =
        assertThrows(HilmirException.class, () -> GatewayJarLocator.findGatewayJar(tempDir));
    assertTrue(e.getMessage().contains("multiple"));
  }

  @Test
  void find_gateway_jar_returns_the_single_match_and_ignores_unrelated_jars() throws Exception {
    Files.writeString(tempDir.resolve("gimle-console-1.0.0.jar"), "not a real jar");
    Path gatewayJar = tempDir.resolve("gimle-gateway-1.0.0.jar");
    Files.writeString(gatewayJar, "not a real jar");

    Path found = GatewayJarLocator.findGatewayJar(tempDir);
    assertEquals(gatewayJar, found);
  }

  @Test
  void derives_the_coordinate_from_the_jars_own_descriptor() {
    Path jar =
        HilmirTestJarBuilder.create()
            .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.gimle.gateway", "1.2.3"))
            .build(tempDir, "gimle-gateway-1.2.3.jar");

    GatewayJarLocator.Coordinate coordinate = GatewayJarLocator.deriveCoordinate(jar);

    assertEquals("com.gimle.gateway", coordinate.name());
    assertEquals(Version.parse("1.2.3"), coordinate.version());
  }

  @Test
  void fails_clearly_when_the_jar_has_no_descriptor_at_all() {
    Path jar = HilmirTestJarBuilder.create().build(tempDir, "gimle-gateway-1.0.0.jar");

    HilmirException e =
        assertThrows(HilmirException.class, () -> GatewayJarLocator.deriveCoordinate(jar));
    assertTrue(e.getMessage().contains("name"));
  }
}
