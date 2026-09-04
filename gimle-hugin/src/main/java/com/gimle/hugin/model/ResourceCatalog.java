package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What {@code :} can open: every collection the control plane lists, plus whatever custom kinds
 * this particular cluster has registered.
 *
 * <p>Discovery is one read of {@code /kinddefinitions} when the prompt is first opened, and it is
 * allowed to fail: a cluster that refuses it, or has no custom kinds at all, still browses every
 * built-in kind. Failing the whole prompt because one optional read did not answer would take away
 * a dozen working screens to report the absence of an eleventh.
 *
 * <p>A custom kind is reached by its plural, its declared short names, or its bare kind name. A
 * built-in key always wins a collision: the built-in set is what this view documents, and a
 * registered kind that happened to call itself {@code tenants} must not be able to redirect a
 * keystroke an operator has already learned.
 */
public final class ResourceCatalog {

  private final List<ResourceKind> kinds;
  private final Map<String, ResourceKind> byAlias;

  private ResourceCatalog(final List<ResourceKind> kinds, final Map<String, ResourceKind> byAlias) {
    this.kinds = List.copyOf(kinds);
    this.byAlias = Map.copyOf(byAlias);
  }

  /** The built-in kinds alone -- what a cluster whose kind definitions cannot be read offers. */
  public static ResourceCatalog builtInOnly() {
    return of(ResourceKind.builtIns(), List.of());
  }

  public static ResourceCatalog discover(final ClusterReader reader) {
    List<Map<String, Object>> definitions;
    try {
      definitions = reader.getList("/kinddefinitions");
    } catch (RuntimeException e) {
      return builtInOnly();
    }
    List<ResourceKind> custom = new ArrayList<>();
    List<List<String>> aliases = new ArrayList<>();
    for (Map<String, Object> definition : definitions) {
      String kindName = text(definition.get("kindName"));
      if (kindName.isBlank()) {
        continue;
      }
      String plural = text(JsonPath.valueAt(definition, "names.plural"));
      String key = lower(plural.isBlank() ? kindName : plural);
      custom.add(
          ResourceKind.fromDefinition(
              kindName,
              key,
              Optional.of(text(definition.get("description"))),
              printColumns(definition.get("printColumns"))));
      aliases.add(aliasesOf(kindName, plural, definition.get("names")));
    }
    List<ResourceKind> all = new ArrayList<>(ResourceKind.builtIns());
    all.addAll(custom);
    return of(all, aliases(custom, aliases));
  }

  private static ResourceCatalog of(
      final List<ResourceKind> kinds, final List<Map.Entry<String, ResourceKind>> extraAliases) {
    Map<String, ResourceKind> index = new LinkedHashMap<>();
    for (Map.Entry<String, ResourceKind> alias : extraAliases) {
      index.putIfAbsent(alias.getKey(), alias.getValue());
    }
    // Built-in and custom keys are written last so a key always beats an alias, and the built-in
    // pass below overwrites any custom kind that claimed a built-in's own key.
    for (ResourceKind kind : kinds) {
      index.put(kind.key(), kind);
    }
    for (ResourceKind kind : ResourceKind.builtIns()) {
      index.put(kind.key(), kind);
    }
    return new ResourceCatalog(kinds, index);
  }

  private static List<Map.Entry<String, ResourceKind>> aliases(
      final List<ResourceKind> custom, final List<List<String>> aliasLists) {
    List<Map.Entry<String, ResourceKind>> entries = new ArrayList<>();
    for (int index = 0; index < custom.size(); index++) {
      for (String alias : aliasLists.get(index)) {
        entries.add(Map.entry(alias, custom.get(index)));
      }
    }
    return entries;
  }

  private static List<String> aliasesOf(
      final String kindName, final String plural, final Object names) {
    List<String> aliases = new ArrayList<>();
    aliases.add(lower(kindName));
    if (!plural.isBlank()) {
      aliases.add(lower(plural));
    }
    if (JsonPath.valueAt(names, "shortNames") instanceof List<?> shortNames) {
      for (Object shortName : shortNames) {
        String alias = lower(text(shortName));
        if (!alias.isBlank()) {
          aliases.add(alias);
        }
      }
    }
    return aliases;
  }

  private static List<ResourceColumn> printColumns(final Object declared) {
    if (!(declared instanceof List<?> list)) {
      return List.of();
    }
    List<ResourceColumn> columns = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      String header = text(map.get("name"));
      String path = text(map.get("path"));
      if (!header.isBlank() && !path.isBlank()) {
        columns.add(ResourceColumn.of(header.toUpperCase(Locale.ROOT), path));
      }
    }
    return columns;
  }

  public List<ResourceKind> kinds() {
    return kinds;
  }

  /** The kind {@code typed} names, by key or by any alias a custom kind declared for itself. */
  public Optional<ResourceKind> resolve(final String typed) {
    return typed == null || typed.isBlank()
        ? Optional.empty()
        : Optional.ofNullable(byAlias.get(lower(typed.trim())));
  }

  /**
   * The kind that browses {@code route}. Looked up by route rather than by a second name mapping,
   * so the cluster view's own {@link WorkloadKind} and the browser cannot drift apart over what
   * {@code /deployments} is called.
   */
  public Optional<ResourceKind> forRoute(final String route) {
    return kinds.stream().filter(kind -> kind.route().equals(route)).findFirst();
  }

  /**
   * What to offer when nothing matched. Kept to the keys that share the typed prefix, since a
   * misspelling is nearly always a near miss and listing all twenty is not a correction.
   */
  public List<String> suggestionsFor(final String typed) {
    String prefix = lower(typed == null ? "" : typed.trim());
    List<String> matches =
        kinds.stream()
            .map(ResourceKind::key)
            .filter(key -> key.startsWith(prefix))
            .sorted()
            .toList();
    return matches.isEmpty() ? kinds.stream().map(ResourceKind::key).sorted().toList() : matches;
  }

  private static String lower(final String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static String text(final Object value) {
    return value instanceof String s ? s : "";
  }
}
