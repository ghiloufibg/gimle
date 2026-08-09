package com.gimle.os.portable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.os.ResourceLimitHandle;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-platform by construction: this limiter has no OS-specific code path, so unlike a
 * cgroup-backed implementation, there's nothing here that only runs correctly on one OS — these
 * tests are the whole story on every platform, not a subset gated to CI.
 */
class PortableJvmFlagsResourceLimiterTest {

  private final PortableJvmFlagsResourceLimiter limiter = new PortableJvmFlagsResourceLimiter();

  @Test
  void supports_tier_1_and_tier_2_but_not_tier_3() {
    assertTrue(limiter.supports(IsolationTier.TIER_1));
    assertTrue(limiter.supports(IsolationTier.TIER_2));
    assertFalse(limiter.supports(IsolationTier.TIER_3));
  }

  @Test
  void prepare_returns_a_handle_carrying_the_requested_limit() {
    ResourceSpec limit = new ResourceSpec("256Mi", "500m");
    ResourceLimitHandle handle = limiter.prepare("worker-1", limit);
    assertEquals("worker-1", handle.workerId());
    assertEquals(limit, handle.limit());
  }

  @Test
  void jvm_flags_derives_xmx_from_memory_limit() {
    ResourceLimitHandle handle = limiter.prepare("worker-1", new ResourceSpec("256Mi", "500m"));
    List<String> flags = limiter.jvmFlags(handle);
    assertTrue(flags.contains("-Xmx" + (256L * 1024 * 1024)));
  }

  @Test
  void jvm_flags_derives_active_processor_count_rounding_up() {
    // 250m -> 1 processor (rounds up, never down to zero).
    ResourceLimitHandle quarterCore =
        limiter.prepare("worker-1", new ResourceSpec("128Mi", "250m"));
    assertTrue(limiter.jvmFlags(quarterCore).contains("-XX:ActiveProcessorCount=1"));

    // 1500m -> 2 processors (ceiling, not floor).
    ResourceLimitHandle oneAndAHalfCores =
        limiter.prepare("worker-1", new ResourceSpec("128Mi", "1500m"));
    assertTrue(limiter.jvmFlags(oneAndAHalfCores).contains("-XX:ActiveProcessorCount=2"));

    // exactly 2000m -> 2 processors.
    ResourceLimitHandle twoCores = limiter.prepare("worker-1", new ResourceSpec("128Mi", "2000m"));
    assertTrue(limiter.jvmFlags(twoCores).contains("-XX:ActiveProcessorCount=2"));
  }

  @Test
  void release_does_not_throw_and_holds_nothing_to_clean_up() {
    ResourceLimitHandle handle = limiter.prepare("worker-1", new ResourceSpec("128Mi", "250m"));
    limiter.release(handle);
    // No assertion beyond "did not throw" — there is genuinely nothing this limiter holds.
  }
}
