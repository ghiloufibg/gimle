package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.config.YamlParsing;
import com.gimle.ragnarok.surtr.SurtrJob.ChurnMode;
import com.gimle.ragnarok.surtr.SurtrJob.ChurnSpec;
import com.gimle.ragnarok.surtr.SurtrWorkload.Gates;
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
      throw new RagnarokException("malformed YAML in workload document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new RagnarokException("workload document must contain a YAML mapping at the root");
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
      throw new RagnarokException("failed reading workload file: " + nameOrPath, e);
    }
  }

  public static SurtrWorkload fromClasspath(final String resource) {
    try (InputStream in =
        SurtrWorkloadParser.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new RagnarokException("no workload resource on the classpath: " + resource);
      }
      return parse(in);
    } catch (final IOException e) {
      throw new RagnarokException("failed reading workload resource: " + resource, e);
    }
  }

  private static SurtrWorkload parseRoot(final Map<?, ?> root) {
    final String name = YamlParsing.requireString(root, "name");
    final String topology = YamlParsing.requireString(root, "topology");
    final List<SurtrJob> jobs = new ArrayList<>();
    for (final Map<?, ?> job : YamlParsing.mapList(root, "jobs")) {
      jobs.add(parseJob(job));
    }
    final List<Measurement> measurements = new ArrayList<>();
    for (final String entry : YamlParsing.stringList(root, "measurements")) {
      measurements.add(Measurement.fromField(entry));
    }
    final Gates gates = parseGates(root.get("gates"));
    final boolean gc = root.get("gc") instanceof Boolean b && b;
    return new SurtrWorkload(name, topology, jobs, measurements, gates, gc);
  }

  private static SurtrJob parseJob(final Map<?, ?> job) {
    final String name = YamlParsing.requireString(job, "name");
    final SurtrJob.Type type = SurtrJob.Type.fromField(YamlParsing.requireString(job, "type"));
    final int iterations = YamlParsing.optionalInt(job, "iterations").orElse(0);
    final double qps = YamlParsing.optionalDouble(job, "qps").orElse(1.0);
    final int burst = YamlParsing.optionalInt(job, "burst").orElse(1);
    final List<ObjectTemplate> objects = new ArrayList<>();
    for (final Map<?, ?> object : YamlParsing.mapList(job, "objects")) {
      objects.add(parseObject(object));
    }
    final Duration waitForActive =
        Duration.ofSeconds(YamlParsing.optionalInt(job, "waitForActiveSeconds").orElse(120));
    final ChurnSpec churn = parseChurn(job.get("churn"));
    final Optional<String> target = YamlParsing.optionalString(job, "target");
    return new SurtrJob(name, type, iterations, qps, burst, objects, waitForActive, churn, target);
  }

  private static ObjectTemplate parseObject(final Map<?, ?> object) {
    final ObjectTemplate.Kind kind =
        ObjectTemplate.Kind.fromField(YamlParsing.requireString(object, "kind"));
    return new ObjectTemplate(
        kind,
        YamlParsing.requireString(object, "name"),
        YamlParsing.optionalString(object, "tenant"),
        YamlParsing.optionalString(object, "module"),
        YamlParsing.optionalInt(object, "replicas").orElse(1));
  }

  private static ChurnSpec parseChurn(final Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> churn)) {
      throw new RagnarokException("'churn' must be a mapping");
    }
    final Duration duration =
        Duration.ofSeconds(YamlParsing.requireInt(churn, "durationSeconds", "churn."));
    final int percent = YamlParsing.requireInt(churn, "percent", "churn.");
    final ChurnMode mode =
        YamlParsing.optionalString(churn, "mode")
            .map(ChurnMode::fromField)
            .orElse(ChurnMode.REDEPLOY);
    return new ChurnSpec(duration, percent, mode);
  }

  private static Gates parseGates(final Object value) {
    if (value == null) {
      return Gates.defaults();
    }
    if (!(value instanceof Map<?, ?> gates)) {
      throw new RagnarokException("'gates' must be a mapping");
    }
    final int maxFailedSubmissions =
        YamlParsing.optionalInt(gates, "maxFailedSubmissions").orElse(0);
    final int maxNeverActive = YamlParsing.optionalInt(gates, "maxNeverActive").orElse(0);
    final Map<String, Long> latency = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : gates.entrySet()) {
      final String key = String.valueOf(entry.getKey());
      if (key.equals("maxFailedSubmissions") || key.equals("maxNeverActive")) {
        continue;
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new RagnarokException("gate '" + key + "' must be a number");
      }
      latency.put(key, number.longValue());
    }
    return new Gates(maxFailedSubmissions, maxNeverActive, latency);
  }
}
