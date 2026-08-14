package com.gimle.holmgang.surtr;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.surtr.SurtrJob.ChurnMode;
import com.gimle.holmgang.surtr.SurtrJob.ChurnSpec;
import com.gimle.holmgang.surtr.SurtrWorkload.Gates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a workload document into a {@link SurtrWorkload}, following the same shape as the topology
 * parser: SnakeYAML restricted to plain maps/lists/scalars via {@code SafeConstructor},
 * hand-validated structural checks here, semantic rules in the record constructors.
 */
public final class SurtrWorkloadParser {

  private SurtrWorkloadParser() {}

  public static SurtrWorkload parse(final InputStream yamlContent) {
    final Object raw;
    try {
      raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
    } catch (final RuntimeException e) {
      throw new HolmgangException("malformed YAML in workload document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new HolmgangException("workload document must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  /**
   * Resolves a workload from either a bundled name ({@code workloads/<name>.yaml} on the classpath)
   * or a filesystem path. A name with no path separator and no {@code .yaml} suffix is a bundled
   * reference workload; anything else is read from disk.
   */
  public static SurtrWorkload resolve(final String nameOrPath) {
    if (!nameOrPath.contains("/") && !nameOrPath.endsWith(".yaml")) {
      return fromClasspath("workloads/" + nameOrPath + ".yaml");
    }
    try (InputStream in = Files.newInputStream(Path.of(nameOrPath))) {
      return parse(in);
    } catch (final IOException e) {
      throw new HolmgangException("failed reading workload file: " + nameOrPath, e);
    }
  }

  public static SurtrWorkload fromClasspath(final String resource) {
    try (InputStream in =
        SurtrWorkloadParser.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new HolmgangException("no workload resource on the classpath: " + resource);
      }
      return parse(in);
    } catch (final IOException e) {
      throw new HolmgangException("failed reading workload resource: " + resource, e);
    }
  }

  private static SurtrWorkload parseRoot(final Map<?, ?> root) {
    final String name = requireString(root, "name");
    final String topology = requireString(root, "topology");
    final List<SurtrJob> jobs = new ArrayList<>();
    for (final Map<?, ?> job : mapList(root, "jobs")) {
      jobs.add(parseJob(job));
    }
    final List<Measurement> measurements = new ArrayList<>();
    for (final String entry : stringList(root, "measurements")) {
      measurements.add(Measurement.fromField(entry));
    }
    final Gates gates = parseGates(root.get("gates"));
    final boolean gc = root.get("gc") instanceof Boolean b && b;
    return new SurtrWorkload(name, topology, jobs, measurements, gates, gc);
  }

  private static SurtrJob parseJob(final Map<?, ?> job) {
    final String name = requireString(job, "name");
    final SurtrJob.Type type = SurtrJob.Type.fromField(requireString(job, "type"));
    final int iterations = optionalInt(job, "iterations").orElse(0);
    final double qps = optionalDouble(job, "qps").orElse(1.0);
    final int burst = optionalInt(job, "burst").orElse(1);
    final List<ObjectTemplate> objects = new ArrayList<>();
    for (final Map<?, ?> object : mapList(job, "objects")) {
      objects.add(parseObject(object));
    }
    final Duration waitForActive =
        Duration.ofSeconds(optionalInt(job, "waitForActiveSeconds").orElse(120));
    final ChurnSpec churn = parseChurn(job.get("churn"));
    final Optional<String> target = optionalString(job, "target");
    return new SurtrJob(name, type, iterations, qps, burst, objects, waitForActive, churn, target);
  }

  private static ObjectTemplate parseObject(final Map<?, ?> object) {
    final ObjectTemplate.Kind kind = ObjectTemplate.Kind.fromField(requireString(object, "kind"));
    return new ObjectTemplate(
        kind,
        requireString(object, "name"),
        optionalString(object, "tenant"),
        optionalString(object, "module"),
        optionalInt(object, "replicas").orElse(1));
  }

  private static ChurnSpec parseChurn(final Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> churn)) {
      throw new HolmgangException("'churn' must be a mapping");
    }
    final Duration duration = Duration.ofSeconds(requireInt(churn, "durationSeconds", "churn."));
    final int percent = requireInt(churn, "percent", "churn.");
    final ChurnMode mode =
        optionalString(churn, "mode").map(ChurnMode::fromField).orElse(ChurnMode.REDEPLOY);
    return new ChurnSpec(duration, percent, mode);
  }

  private static Gates parseGates(final Object value) {
    if (value == null) {
      return Gates.defaults();
    }
    if (!(value instanceof Map<?, ?> gates)) {
      throw new HolmgangException("'gates' must be a mapping");
    }
    final int maxFailedSubmissions = optionalInt(gates, "maxFailedSubmissions").orElse(0);
    final int maxNeverActive = optionalInt(gates, "maxNeverActive").orElse(0);
    final Map<String, Long> latency = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : gates.entrySet()) {
      final String key = String.valueOf(entry.getKey());
      if (key.equals("maxFailedSubmissions") || key.equals("maxNeverActive")) {
        continue;
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new HolmgangException("gate '" + key + "' must be a number");
      }
      latency.put(key, number.longValue());
    }
    return new Gates(maxFailedSubmissions, maxNeverActive, latency);
  }

  private static String requireString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new HolmgangException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Optional<String> optionalString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new HolmgangException("field must be a non-blank string if present: " + key);
    }
    return Optional.of(s);
  }

  private static Optional<Integer> optionalInt(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new HolmgangException("field must be numeric if present: " + key);
    }
    return Optional.of(number.intValue());
  }

  private static Optional<Double> optionalDouble(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new HolmgangException("field must be numeric if present: " + key);
    }
    return Optional.of(number.doubleValue());
  }

  private static int requireInt(final Map<?, ?> map, final String key, final String prefix) {
    final Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new HolmgangException("missing or non-numeric required field: " + prefix + key);
    }
    return number.intValue();
  }

  private static List<String> stringList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new HolmgangException("'" + key + "' must be a list");
    }
    final List<String> strings = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new HolmgangException("each '" + key + "' entry must be a non-blank string");
      }
      strings.add(s);
    }
    return strings;
  }

  private static List<Map<?, ?>> mapList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new HolmgangException("'" + key + "' must be a list");
    }
    final List<Map<?, ?>> maps = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof Map<?, ?> m)) {
        throw new HolmgangException("each '" + key + "' entry must be a mapping");
      }
      maps.add(m);
    }
    return maps;
  }
}
