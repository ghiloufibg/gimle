package com.gimle.hilmir.analyze;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.Version;
import java.util.List;
import java.util.Optional;

/**
 * A lenient read of a jar's own {@code META-INF/gimle/gimle-module.yaml}, deliberately more
 * forgiving than {@code ModuleDescriptorParser}: each field is extracted independently, so one bad
 * field (an unparseable resource quantity, say) doesn't prevent every other field from being
 * available for its own finding -- {@code doctor} wants to report every problem a manifest has in
 * one pass, not stop at the first one the way a real deploy's strict parser correctly does.
 */
public record DescriptorSnapshot(
    boolean present,
    Optional<String> name,
    Optional<Version> version,
    Optional<IsolationTier> isolationTier,
    Optional<String> requestMemory,
    Optional<String> requestCpu,
    Optional<String> limitMemory,
    Optional<String> limitCpu,
    Optional<String> livenessClass,
    Optional<String> readinessClass,
    Optional<String> lifecycleHooksClass,
    Optional<String> jobHooksClass,
    List<String> parseIssues) {

  public static final DescriptorSnapshot ABSENT =
      new DescriptorSnapshot(
          false,
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
          List.of());

  public DescriptorSnapshot {
    parseIssues = List.copyOf(parseIssues);
  }

  public boolean hasResources() {
    return requestMemory.isPresent()
        && requestCpu.isPresent()
        && limitMemory.isPresent()
        && limitCpu.isPresent();
  }
}
