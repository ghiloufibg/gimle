package com.gimle.module.leak;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import com.gimle.module.layer.ModuleLayerFactory;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.layer.PlatformLayer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Acceptance-test driver for retaining-path attribution. Run as a <em>separate process</em>
 * launched with {@code path-to-gc-roots=true} (a {@code -XX:StartFlightRecording} option) -- {@link
 * OldObjectSampleCorrelator}'s own javadoc documents why that can't be enabled from inside this
 * process. Calls {@link OldObjectSampleCorrelator#findRetainingPath} directly (rather than going
 * through {@link LeakTracker}, which only gets one attempt per tracked loader and would race this
 * driver's own warm-up), at the end of a sustained high-throughput allocation burst of the fixture
 * class -- the population JFR's old-object sampler needs to have a realistic chance of picking one
 * up.
 *
 * <p>The sampler is a small, weighted reservoir with no guarantee any specific class gets sampled
 * within a bounded window (confirmed empirically: even a sustained high-throughput burst
 * occasionally samples zero fixture-class objects at all in the time available) -- a production
 * worker gets effectively unlimited such windows over its real lifetime, but a single bounded test
 * run does not, so this retries the whole burst-and-dump cycle a few times rather than asserting
 * success on one attempt, which would be flaky by construction.
 */
public final class RetainingPathDriver {

  private static final int MAX_ATTEMPTS = 4;
  private static final List<Object> RETAINED_POPULATION = new ArrayList<>();

  private RetainingPathDriver() {}

  public static void main(String[] args) throws Exception {
    Path jarPath = Path.of(args[0]);
    long attemptBudgetMillis = Long.parseLong(args[1]);

    ModuleInstanceId id =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.fixture.retaining", Version.parse("1.0.0")));
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ModuleLayerHandle handle =
        ModuleLayerFactory.create(
            id, jarPath, List.of(platform), ClassLoader.getSystemClassLoader());
    Class<?> payloadClass = handle.loader().loadClass("com.gimle.fixture.retaining.Payload");
    Set<String> packages =
        handle.layer().modules().stream()
            .flatMap(m -> m.getPackages().stream())
            .collect(Collectors.toUnmodifiableSet());

    Optional<String> found = Optional.empty();
    for (int attempt = 1; attempt <= MAX_ATTEMPTS && found.isEmpty(); attempt++) {
      allocationBurst(payloadClass, attemptBudgetMillis);
      try (OldObjectSampleCorrelator correlator = new OldObjectSampleCorrelator()) {
        found = correlator.findRetainingPath(packages);
      }
    }

    if (found.isPresent()) {
      System.out.println("RETAINING_PATH_PRESENT: " + found.get());
    } else {
      System.out.println("RETAINING_PATH_ABSENT");
    }
    System.out.println("DONE");
  }

  /**
   * High allocation <em>rate</em> of the fixture class matters here, not just eventual live count:
   * JFR's old-object sampler triggers off TLAB refills roughly proportional to allocation volume,
   * so a slow trickle of a few large objects is far less likely to ever get sampled at all than a
   * high-throughput stream -- confirmed empirically against this same mechanism.
   */
  private static void allocationBurst(Class<?> payloadClass, long durationMillis)
      throws ReflectiveOperationException, InterruptedException {
    long deadline = System.currentTimeMillis() + durationMillis;
    while (System.currentTimeMillis() < deadline) {
      for (int i = 0; i < 5000; i++) {
        RETAINED_POPULATION.add(payloadClass.getConstructor().newInstance());
      }
      if (RETAINED_POPULATION.size() > 20_000) {
        RETAINED_POPULATION.clear();
      }
      System.gc();
      Thread.sleep(100);
    }
  }
}
