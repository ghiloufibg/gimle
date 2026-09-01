package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The CLI's own on-disk config: a set of named control-plane endpoints plus which one is currently
 * selected, at {@code ~/.gimle/config} (the same {@code ~/.gimle} directory the rest of this
 * project's local state already lives under). {@code -Dgimle.cli.configFile=<path>} points at a
 * different file instead.
 *
 * <p>The file is optional in the strongest sense -- a CLI that has never run {@code gimle context
 * set} never creates it, and every command still works exactly as before off {@code --server}/
 * {@code GIMLE_SERVER}. It is written with owner-only permissions where the filesystem supports
 * POSIX ones, and holds endpoints only: see {@link CliContext}.
 *
 * <pre>
 *   currentContext: "prod"
 *   contexts:
 *     - name: "prod"
 *       server: "cp.prod.internal:8080"
 *     - name: "dev"
 *       server: "127.0.0.1:8080"
 * </pre>
 */
record CliConfig(Optional<String> currentContext, List<CliContext> contexts) {

  static final String CONFIG_FILE_PROPERTY = "gimle.cli.configFile";

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  CliConfig {
    contexts = List.copyOf(contexts);
  }

  static CliConfig empty() {
    return new CliConfig(Optional.empty(), List.of());
  }

  static Path defaultPath() {
    String override = System.getProperty(CONFIG_FILE_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home", "."), ".gimle", "config");
  }

  /**
   * Reads {@code path}, treating a missing file as an empty config -- by far the common case, and
   * not something to report as a problem. Anything else wrong with the file (unreadable, not YAML,
   * the right YAML shape but nonsense contents) throws {@link CliException} naming the file and
   * what is wrong with it, so an operator editing it by hand is told rather than left with a
   * silently ignored file.
   */
  static CliConfig load(Path path) {
    String text;
    try {
      if (!Files.exists(path)) {
        return empty();
      }
      text = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CliException("could not read " + path + ": " + e.getMessage(), e);
    }
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    } catch (RuntimeException e) {
      throw new CliException(path + " is not valid YAML: " + e.getMessage(), e);
    }
    if (root == null) {
      return empty();
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new CliException(path + " must contain a YAML mapping, found " + typeName(root));
    }
    return new CliConfig(readCurrentContext(path, map), readContexts(path, map));
  }

  private static Optional<String> readCurrentContext(Path path, Map<?, ?> map) {
    Object value = map.get("currentContext");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String name) || name.isBlank()) {
      throw new CliException(path + ": currentContext must be a non-empty string");
    }
    return Optional.of(name);
  }

  private static List<CliContext> readContexts(Path path, Map<?, ?> map) {
    Object value = map.get("contexts");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new CliException(path + ": contexts must be a list, found " + typeName(value));
    }
    List<CliContext> parsed = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> fields)) {
        throw new CliException(
            path + ": every contexts entry must be a mapping, found " + typeName(entry));
      }
      String name = requireString(path, fields.get("name"), "name");
      String server = requireString(path, fields.get("server"), "server");
      if (!names.add(name)) {
        throw new CliException(path + ": context '" + name + "' is defined more than once");
      }
      parsed.add(new CliContext(name, server));
    }
    return parsed;
  }

  private static String requireString(Path path, Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new CliException(path + ": every contexts entry needs a non-empty " + field);
    }
    return text;
  }

  private static String typeName(Object value) {
    return value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
  }

  /**
   * Written through a sibling temp file and an atomic rename, so an interrupted write leaves the
   * previous config intact rather than a half-written file every later invocation would reject.
   */
  void save(Path path) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
        restrict(parent, "rwx------");
      }
      Path temp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(temp, render(), StandardCharsets.UTF_8);
      restrict(temp, "rw-------");
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new CliException("could not write " + path + ": " + e.getMessage(), e);
    }
  }

  private String render() {
    StringBuilder yaml = new StringBuilder();
    currentContext.ifPresent(
        name -> yaml.append("currentContext: ").append(Json.write(name)).append('\n'));
    yaml.append("contexts:\n");
    for (CliContext context : contexts) {
      yaml.append("  - name: ").append(Json.write(context.name())).append('\n');
      yaml.append("    server: ").append(Json.write(context.server())).append('\n');
    }
    return yaml.toString();
  }

  /** Best-effort: a filesystem without POSIX permissions simply gets the default ones. */
  private static void restrict(Path path, String permissions) {
    if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (IOException | UnsupportedOperationException e) {
      // The config carries no secret material, so a filesystem that refuses the tightening is
      // not a reason to refuse the write.
    }
  }

  Optional<CliContext> find(String name) {
    return contexts.stream().filter(context -> context.name().equals(name)).findFirst();
  }

  CliConfig withContext(CliContext context) {
    List<CliContext> updated = new ArrayList<>();
    boolean replaced = false;
    for (CliContext existing : contexts) {
      if (existing.name().equals(context.name())) {
        updated.add(context);
        replaced = true;
      } else {
        updated.add(existing);
      }
    }
    if (!replaced) {
      updated.add(context);
    }
    // A first context becomes the current one: a config holding exactly one endpoint and no
    // selection would otherwise still need a separate 'context use' before it did anything.
    Optional<String> current =
        currentContext.isPresent() ? currentContext : Optional.of(context.name());
    return new CliConfig(current, updated);
  }

  CliConfig withoutContext(String name) {
    List<CliContext> remaining =
        contexts.stream().filter(context -> !context.name().equals(name)).toList();
    Optional<String> current = currentContext.filter(selected -> !selected.equals(name));
    return new CliConfig(current, remaining);
  }

  CliConfig withCurrentContext(String name) {
    return new CliConfig(Optional.of(name), contexts);
  }

  static void requireValidName(String name) {
    if (!NAME.matcher(name).matches()) {
      throw new CliException(
          "invalid context name: "
              + name
              + " (letters, digits, '.', '_' and '-', starting with a letter or digit)");
    }
  }
}
