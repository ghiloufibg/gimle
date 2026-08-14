package com.gimle.holmgang.saga;

import com.gimle.holmgang.junit.Holmgang;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Folds the plain-JUnit integration tests ({@code *IT} classes that aren't Cucumber) into the same
 * report the {@link SagaCucumberPlugin} builds, so a run's record is complete regardless of a
 * test's type -- each IT class becomes a "feature", each {@code @Test} a "scenario" with pass/fail
 * and duration. Auto-registered via {@code META-INF/services}.
 *
 * <p>Two things are deliberately excluded to avoid double-counting: the {@code cucumber} engine's
 * tests (already captured by the plugin) and {@code SurtrIT} (already represented in its own rich
 * {@code surtr} section). Only {@code *IT} classes are considered, so a plain unit build -- which
 * runs no {@code *IT} -- records nothing and never even creates the collector.
 */
public final class SagaJUnitListener implements TestExecutionListener {

  private static final String SURTR_IT = "com.gimle.holmgang.SurtrIT";

  private final Map<String, Long> startNanos = new ConcurrentHashMap<>();

  @Override
  public void executionStarted(final TestIdentifier id) {
    if (id.isTest()) {
      startNanos.put(id.getUniqueId(), System.nanoTime());
    }
  }

  @Override
  public void executionSkipped(final TestIdentifier id, final String reason) {
    if (shouldRecord(id)) {
      record(id, "SKIPPED", 0, null);
    }
  }

  @Override
  public void executionFinished(final TestIdentifier id, final TestExecutionResult result) {
    final Long start = startNanos.remove(id.getUniqueId());
    if (!shouldRecord(id)) {
      return;
    }
    final long durationMillis = start == null ? 0 : (System.nanoTime() - start) / 1_000_000L;
    record(id, mapStatus(result.getStatus()), durationMillis, result.getThrowable().orElse(null));
  }

  private void record(
      final TestIdentifier id,
      final String status,
      final long durationMillis,
      final Throwable error) {
    final String className = classNameOf(id);
    final String simpleName = className.substring(className.lastIndexOf('.') + 1);
    final Map<String, Object> scenario = new LinkedHashMap<>();
    scenario.put("name", stripParens(id.getDisplayName()));
    scenario.put("status", status);
    scenario.put("durationMillis", durationMillis);
    scenario.put("topology", topologyOf(className));
    scenario.put("tags", tagsOf(id));
    scenario.put("steps", new ArrayList<>());
    scenario.put("failure", SagaCollector.failureFrom(error));
    SagaCollector.instance().addScenario(simpleName, className, scenario);
  }

  private boolean shouldRecord(final TestIdentifier id) {
    if (!id.isTest() || id.getUniqueId().contains("[engine:cucumber]")) {
      return false;
    }
    final String className = classNameOf(id);
    return className != null && className.endsWith("IT") && !className.equals(SURTR_IT);
  }

  private static String classNameOf(final TestIdentifier id) {
    return id.getSource().orElse(null) instanceof MethodSource source
        ? source.getClassName()
        : null;
  }

  /** The topology a {@link Holmgang}-annotated IT booted, reduced to its bare name; else empty. */
  private static String topologyOf(final String className) {
    try {
      final Holmgang annotation = Class.forName(className).getAnnotation(Holmgang.class);
      if (annotation == null) {
        return "";
      }
      final String resource = annotation.topology();
      final String base = resource.substring(resource.lastIndexOf('/') + 1);
      return base.endsWith(".yaml") ? base.substring(0, base.length() - ".yaml".length()) : base;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return "";
    }
  }

  private static List<String> tagsOf(final TestIdentifier id) {
    final List<String> tags = new ArrayList<>();
    id.getTags().forEach(tag -> tags.add("@" + tag.getName()));
    return tags;
  }

  private static String stripParens(final String displayName) {
    return displayName.endsWith("()")
        ? displayName.substring(0, displayName.length() - 2)
        : displayName;
  }

  private static String mapStatus(final TestExecutionResult.Status status) {
    return switch (status) {
      case SUCCESSFUL -> "PASSED";
      case FAILED -> "FAILED";
      case ABORTED -> "SKIPPED";
    };
  }
}
