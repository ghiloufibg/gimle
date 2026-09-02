package com.gimle.controlplane;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of the console's bundled addons this control plane advertises.
 *
 * <p>An addon always ships inside the console bundle, so "disabled" cannot mean "not present" -- it
 * means this process does not advertise it: the sidebar grows no entry, and the addon's own route
 * explains itself rather than rendering. The switch belongs to the control-plane deployment,
 * alongside its other {@code -Dgimle.controlplane.*} properties, so the operator who sets a Muninn
 * endpoint sets this the same way.
 *
 * <p>The catalog of what is bundled is the console's own {@code console/addons.json}, read off the
 * classpath beside the bundled SPA -- never a list maintained here, which could drift from the one
 * the console actually ships.
 */
public final class ConsoleAddons {

  /** Comma-separated addon ids to advertise; the literal {@code none} advertises nothing. */
  public static final String PROPERTY = "gimle.controlplane.consoleAddons";

  private static final String NONE = "none";

  private final List<String> bundledIds;
  private final Set<String> enabledIds;

  private ConsoleAddons(List<String> bundledIds, Set<String> enabledIds) {
    this.bundledIds = List.copyOf(bundledIds);
    this.enabledIds = Set.copyOf(enabledIds);
  }

  /**
   * Reads the bundled catalog and resolves the property against it.
   *
   * @throws IllegalArgumentException naming every bundled id, when the property names one that is
   *     not bundled -- the same fail-fast posture the rest of this process takes on a
   *     misconfiguration, rather than starting up silently advertising nothing.
   */
  public static ConsoleAddons resolve(ClassLoader classLoader, String rawProperty) {
    List<String> bundled = readBundledIds(classLoader);
    if (rawProperty == null || rawProperty.isBlank()) {
      // Default: everything bundled is on. A console screen opens no listener and holds no state,
      // so unlike an actual subsystem there is no cost to being on; the property exists to turn a
      // screen off for a deployment that does not want it.
      return new ConsoleAddons(bundled, new LinkedHashSet<>(bundled));
    }
    if (NONE.equalsIgnoreCase(rawProperty.strip())) {
      return new ConsoleAddons(bundled, Set.of());
    }
    Set<String> requested = new LinkedHashSet<>();
    for (String token : rawProperty.split(",")) {
      String id = token.strip();
      if (id.isEmpty()) {
        continue;
      }
      if (!bundled.contains(id)) {
        throw new IllegalArgumentException(
            "-D"
                + PROPERTY
                + " names '"
                + id
                + "', which this console does not bundle; bundled addons: "
                + (bundled.isEmpty() ? "(none)" : String.join(", ", bundled)));
      }
      requested.add(id);
    }
    return new ConsoleAddons(bundled, requested);
  }

  /** Resolves against the property as this JVM was started with it. */
  public static ConsoleAddons fromSystemProperty(ClassLoader classLoader) {
    return resolve(classLoader, System.getProperty(PROPERTY));
  }

  /** Every id the console bundles, in catalog order. */
  public List<String> bundledIds() {
    return bundledIds;
  }

  public boolean isEnabled(String id) {
    return enabledIds.contains(id);
  }

  /** The {@code GET /console/addons} body: every bundled addon, each with its own verdict. */
  public Map<String, Object> toJson() {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (String id : bundledIds) {
      entries.add(Map.of("id", id, "enabled", enabledIds.contains(id)));
    }
    return Map.of("addons", entries);
  }

  /**
   * The ids in {@code console/addons.json}. A console built without the file (or no console on the
   * classpath at all) bundles no addons -- an empty catalog, not a failure: this process serves
   * whatever console it was given.
   */
  private static List<String> readBundledIds(ClassLoader classLoader) {
    try (InputStream in = classLoader.getResourceAsStream("console/addons.json")) {
      if (in == null) {
        return List.of();
      }
      Map<String, Object> root =
          Json.asObject(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      List<String> ids = new ArrayList<>();
      for (Map<String, Object> entry : Json.asObjectList(root.get("addons"))) {
        Object id = entry.get("id");
        if (id instanceof String s && !s.isBlank()) {
          ids.add(s);
        }
      }
      return List.copyOf(ids);
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading console/addons.json off the classpath", e);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "console/addons.json on the classpath is not a readable addon catalog: "
              + e.getMessage());
    }
  }
}
