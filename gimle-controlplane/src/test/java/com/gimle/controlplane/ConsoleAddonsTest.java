package com.gimle.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deployment switch behind the console's bundled addons: which ids exist is the console's own
 * {@code console/addons.json}, which of them are advertised is this process's own property.
 */
class ConsoleAddonsTest {

  private static ClassLoader catalogOf(Path dir, String json) throws IOException {
    Path console = dir.resolve("console");
    Files.createDirectories(console);
    Files.writeString(console.resolve("addons.json"), json, StandardCharsets.UTF_8);
    return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
  }

  private static ClassLoader twoAddons(Path dir) throws IOException {
    return catalogOf(
        dir,
        """
        {"addons": [
          {"id": "gateway", "title": "Gateway", "description": "d", "route": "/gateway"},
          {"id": "skald", "title": "Skald DNS", "description": "d", "route": "/skald"}
        ]}
        """);
  }

  @Test
  void the_default_advertises_every_bundled_addon(@TempDir Path dir) throws Exception {
    ConsoleAddons addons = ConsoleAddons.resolve(twoAddons(dir), null);

    assertEquals(List.of("gateway", "skald"), addons.bundledIds());
    assertTrue(addons.isEnabled("gateway"));
    assertTrue(addons.isEnabled("skald"));
  }

  @Test
  void a_blank_property_is_the_same_as_no_property(@TempDir Path dir) throws Exception {
    assertTrue(ConsoleAddons.resolve(twoAddons(dir), "   ").isEnabled("gateway"));
  }

  @Test
  void none_advertises_nothing_while_still_reporting_what_is_bundled(@TempDir Path dir)
      throws Exception {
    ConsoleAddons addons = ConsoleAddons.resolve(twoAddons(dir), "none");

    assertFalse(addons.isEnabled("gateway"));
    assertFalse(addons.isEnabled("skald"));
    // Still bundled, so the console can explain each disabled screen rather than 404 it.
    assertEquals(List.of("gateway", "skald"), addons.bundledIds());
  }

  @Test
  void a_named_subset_advertises_only_what_it_names(@TempDir Path dir) throws Exception {
    ConsoleAddons addons = ConsoleAddons.resolve(twoAddons(dir), " skald ");

    assertFalse(addons.isEnabled("gateway"));
    assertTrue(addons.isEnabled("skald"));
  }

  @Test
  void an_unknown_id_fails_startup_naming_the_bundled_ids(@TempDir Path dir) throws Exception {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ConsoleAddons.resolve(twoAddons(dir), "gateway,ygdrasil"));

    assertTrue(e.getMessage().contains("ygdrasil"));
    assertTrue(e.getMessage().contains("gateway, skald"), e.getMessage());
  }

  /**
   * A console built without the catalog bundles nothing -- an empty set, never a startup failure.
   */
  @Test
  void a_console_with_no_catalog_bundles_nothing(@TempDir Path dir) throws Exception {
    ConsoleAddons addons =
        ConsoleAddons.resolve(new URLClassLoader(new URL[] {dir.toUri().toURL()}, null), null);

    assertEquals(List.of(), addons.bundledIds());
    assertFalse(addons.isEnabled("gateway"));
  }

  @Test
  void the_json_body_carries_a_verdict_per_bundled_addon(@TempDir Path dir) throws Exception {
    Map<String, Object> body = ConsoleAddons.resolve(twoAddons(dir), "gateway").toJson();

    // Json.asObjectList, not a cast plus @SuppressWarnings: one place in the codebase carries that
    // suppression so no call site has to.
    List<Map<String, Object>> entries = Json.asObjectList(body.get("addons"));
    assertEquals(2, entries.size());
    assertEquals(Map.of("id", "gateway", "enabled", true), entries.get(0));
    assertEquals(Map.of("id", "skald", "enabled", false), entries.get(1));
  }

  /** The one catalog shape that is a real misconfiguration rather than an absent console. */
  @Test
  void an_unreadable_catalog_fails_rather_than_silently_bundling_nothing(@TempDir Path dir)
      throws Exception {
    ClassLoader broken = catalogOf(dir, "not json at all");

    assertThrows(IllegalArgumentException.class, () -> ConsoleAddons.resolve(broken, null));
  }
}
