package com.gimle.ragnarok.fenrir;

import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.config.YamlParsing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a chaos plan document into a {@link FenrirPlan}, following the same shape as {@link
 * com.gimle.ragnarok.surtr.SurtrWorkloadParser}: {@code SafeConstructor}-only SnakeYAML,
 * hand-validated structural checks here, semantic rules left entirely to {@link
 * FenrirPlan.Builder#build()} and {@link Pool}'s own compact constructor -- a malformed plan fails
 * with the identical message whether it was built from YAML or from Java.
 */
public final class ChaosPlanParser {

  private ChaosPlanParser() {}

  public static FenrirPlan parse(final InputStream yamlContent) {
    final Object raw;
    try {
      raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
    } catch (final RuntimeException e) {
      throw new RagnarokException("malformed YAML in chaos plan document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new RagnarokException("chaos plan document must contain a YAML mapping at the root");
    }
    return fromMap(root);
  }

  /**
   * Resolves a plan from either a bundled name ({@code chaos-plans/<name>.yaml} on the classpath)
   * or a filesystem path -- the same naming convention {@link
   * com.gimle.ragnarok.surtr.SurtrWorkloadParser#resolve(String)} already uses.
   */
  public static FenrirPlan resolve(final String nameOrPath) {
    if (!nameOrPath.contains("/") && !nameOrPath.endsWith(".yaml")) {
      return fromClasspath("chaos-plans/" + nameOrPath + ".yaml");
    }
    try (InputStream in = Files.newInputStream(Path.of(nameOrPath))) {
      return parse(in);
    } catch (final IOException e) {
      throw new RagnarokException("failed reading chaos plan file: " + nameOrPath, e);
    }
  }

  public static FenrirPlan fromClasspath(final String resource) {
    try (InputStream in = ChaosPlanParser.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new RagnarokException("no chaos plan resource on the classpath: " + resource);
      }
      return parse(in);
    } catch (final IOException e) {
      throw new RagnarokException("failed reading chaos plan resource: " + resource, e);
    }
  }

  /**
   * Builds a plan straight from an already-decoded {@code Map}/{@code List}/{@code String}/{@code
   * Number}/{@code Boolean} tree -- the same shape both SnakeYAML's {@code SafeConstructor} and
   * {@code Json.parse} produce, so this one method backs both {@link #parse(InputStream)} (a chaos
   * plan document) and {@code ReplayCommand} (a plan embedded inside a previous {@code chaos} run's
   * own JSON report).
   */
  public static FenrirPlan fromMap(final Map<?, ?> root) {
    final long seed = YamlParsing.optionalLong(root, "seed").orElse(0L);
    final FenrirPlan.Builder builder = FenrirPlan.seeded(seed);
    YamlParsing.optionalInt(root, "soakSeconds")
        .ifPresent(seconds -> builder.soakFor(Duration.ofSeconds(seconds)));
    parseStrikeGap(root, builder);
    final var deployments = YamlParsing.stringList(root, "eligibleDeployments");
    if (!deployments.isEmpty()) {
      builder.eligibleDeployments(deployments.toArray(new String[0]));
    }
    YamlParsing.optionalBoolean(root, "convergeBetweenFaults")
        .ifPresent(builder::convergeBetweenFaults);
    YamlParsing.optionalInt(root, "gateTimeoutSeconds")
        .ifPresent(seconds -> builder.gateTimeout(Duration.ofSeconds(seconds)));
    for (final Map<?, ?> pool : YamlParsing.mapList(root, "pools")) {
      builder.pool(parsePool(pool));
    }
    return builder.build();
  }

  private static void parseStrikeGap(final Map<?, ?> root, final FenrirPlan.Builder builder) {
    final var fixed = YamlParsing.optionalInt(root, "strikeEverySeconds");
    if (fixed.isPresent()) {
      builder.strikeEvery(Duration.ofSeconds(fixed.get()));
      return;
    }
    final var min = YamlParsing.optionalInt(root, "strikeEveryMinSeconds");
    final var max = YamlParsing.optionalInt(root, "strikeEveryMaxSeconds");
    if (min.isPresent() && max.isPresent()) {
      builder.strikeEvery(Duration.ofSeconds(min.get()), Duration.ofSeconds(max.get()));
    } else if (min.isPresent() || max.isPresent()) {
      throw new RagnarokException(
          "strikeEveryMinSeconds and strikeEveryMaxSeconds must both be set, or neither");
    }
  }

  private static Pool parsePool(final Map<?, ?> pool) {
    final String kindField = YamlParsing.requireString(pool, "kind");
    final FaultKind kind;
    try {
      kind = FaultKind.valueOf(kindField);
    } catch (final IllegalArgumentException e) {
      throw new RagnarokException(
          "unknown fault kind '"
              + kindField
              + "' (expected one of "
              + java.util.Arrays.toString(FaultKind.values())
              + ")");
    }
    Pool built = poolFor(kind);
    final var weight = YamlParsing.optionalInt(pool, "weight");
    if (weight.isPresent()) {
      built = built.weight(weight.get());
    }
    final var dwellSeconds = YamlParsing.optionalInt(pool, "dwellSeconds");
    final var healAfterSeconds = YamlParsing.optionalInt(pool, "healAfterSeconds");
    if (dwellSeconds.isPresent() && healAfterSeconds.isPresent()) {
      throw new RagnarokException(
          "a pool must set at most one of dwellSeconds/healAfterSeconds, not both");
    }
    if (dwellSeconds.isPresent()) {
      built = built.dwell(Duration.ofSeconds(dwellSeconds.get()));
    } else if (healAfterSeconds.isPresent()) {
      built = built.healAfter(Duration.ofSeconds(healAfterSeconds.get()));
    }
    return built;
  }

  private static Pool poolFor(final FaultKind kind) {
    return switch (kind) {
      case WORKER_KILL -> Pools.workerKills();
      case STORE_BOUNCE -> Pools.storeBounces();
      case LEADER_BOUNCE -> Pools.leaderBounces();
      case CONTROL_PLANE_BOUNCE -> Pools.controlPlaneBounces();
      case LINK_CUT -> Pools.linkCuts();
      case STORE_PARTITION -> Pools.storePartitions();
      case FAFNIR_BOUNCE -> Pools.fafnirBounces();
      case MUNINN_BOUNCE -> Pools.muninnBounces();
      case ANDVARI_BOUNCE -> Pools.andvariBounces();
    };
  }
}
