package com.gimle.hilmir.analyze;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.Version;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code META-INF/gimle/gimle-module.yaml} content into a {@link DescriptorSnapshot} -- the
 * same YAML schema and {@code SafeConstructor} convention {@code ModuleDescriptorParser} uses
 * (untrusted input, so tag-driven arbitrary type instantiation stays disabled), duplicated locally
 * rather than depending on {@code gimle-module} for it, and deliberately lenient field-by-field
 * rather than a single strict top-to-bottom parse -- see {@link DescriptorSnapshot}'s own javadoc.
 */
public final class DescriptorReader {

  private DescriptorReader() {}

  public static DescriptorSnapshot read(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      return new DescriptorSnapshot(
          true,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of("malformed YAML: " + e.getMessage()));
    }
    if (!(raw instanceof Map<?, ?> root)) {
      return new DescriptorSnapshot(
          true,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of("gimle-module.yaml must contain a YAML mapping at the root"));
    }

    List<String> issues = new ArrayList<>();
    Optional<String> name = stringField(root, "name");
    Optional<Version> version = parseVersion(root, issues);
    Optional<IsolationTier> tier = parseTier(root, issues);

    Map<?, ?> resources = mapField(root, "resources");
    Map<?, ?> request = resources == null ? null : mapField(resources, "request");
    Map<?, ?> limit = resources == null ? null : mapField(resources, "limit");
    Optional<String> requestMemory =
        request == null ? Optional.empty() : stringField(request, "memory");
    Optional<String> requestCpu = request == null ? Optional.empty() : stringField(request, "cpu");
    Optional<String> limitMemory = limit == null ? Optional.empty() : stringField(limit, "memory");
    Optional<String> limitCpu = limit == null ? Optional.empty() : stringField(limit, "cpu");

    Map<?, ?> health = mapField(root, "health");
    Optional<String> liveness = health == null ? Optional.empty() : stringField(health, "liveness");
    Optional<String> readiness =
        health == null ? Optional.empty() : stringField(health, "readiness");

    Map<?, ?> lifecycle = mapField(root, "lifecycle");
    Optional<String> hooks = lifecycle == null ? Optional.empty() : stringField(lifecycle, "hooks");
    Optional<String> jobHooks =
        lifecycle == null ? Optional.empty() : stringField(lifecycle, "jobHooks");

    return new DescriptorSnapshot(
        true,
        name,
        version,
        tier,
        requestMemory,
        requestCpu,
        limitMemory,
        limitCpu,
        liveness,
        readiness,
        hooks,
        jobHooks,
        issues);
  }

  private static Optional<Version> parseVersion(Map<?, ?> root, List<String> issues) {
    Optional<String> raw = stringField(root, "version");
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Version.parse(raw.get()));
    } catch (IllegalArgumentException e) {
      issues.add("malformed version: " + e.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<IsolationTier> parseTier(Map<?, ?> root, List<String> issues) {
    Map<?, ?> isolation = mapField(root, "isolation");
    if (isolation == null) {
      return Optional.empty();
    }
    Optional<String> raw = stringField(isolation, "tier");
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(IsolationTier.valueOf(raw.get()));
    } catch (IllegalArgumentException e) {
      issues.add("invalid isolation.tier: " + raw.get());
      return Optional.empty();
    }
  }

  private static Optional<String> stringField(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
  }

  private static Map<?, ?> mapField(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value instanceof Map<?, ?> m ? m : null;
  }
}
