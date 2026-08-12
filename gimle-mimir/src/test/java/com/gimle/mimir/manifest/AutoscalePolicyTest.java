package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * {@link AutoscalePolicy.CombinationMode}/per-signal-weight validation and defaulting (roadmap item
 * 10) -- the four target-signal validations these tests don't re-cover are already pinned by the
 * codebase's existing manifest-level tests ({@code DeploymentManifestParserTest}).
 */
class AutoscalePolicyTest {

  @Test
  void the_three_arg_constructor_defaults_to_worst_signal_with_no_weights() {
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);

    assertEquals(AutoscalePolicy.CombinationMode.WORST_SIGNAL, policy.combinationMode());
    assertEquals(OptionalDouble.empty(), policy.cpuWeight());
    assertEquals(OptionalDouble.empty(), policy.requestRateWeight());
    assertEquals(OptionalDouble.empty(), policy.errorRateWeight());
    assertEquals(OptionalDouble.empty(), policy.queueDepthWeight());
  }

  @Test
  void the_six_arg_constructor_defaults_to_worst_signal_with_no_weights() {
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1, 5, 50, OptionalDouble.of(10.0), OptionalDouble.empty(), OptionalInt.empty());

    assertEquals(AutoscalePolicy.CombinationMode.WORST_SIGNAL, policy.combinationMode());
    assertEquals(OptionalDouble.empty(), policy.cpuWeight());
  }

  @Test
  void a_null_combination_mode_defaults_to_worst_signal() {
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            null,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty());

    assertEquals(AutoscalePolicy.CombinationMode.WORST_SIGNAL, policy.combinationMode());
  }

  @Test
  void a_weighted_mode_policy_accepts_a_positive_weight_per_configured_signal() {
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.of(10.0),
            OptionalDouble.of(5.0),
            OptionalInt.of(20),
            AutoscalePolicy.CombinationMode.WEIGHTED,
            OptionalDouble.of(1.0),
            OptionalDouble.of(3.0),
            OptionalDouble.of(2.0),
            OptionalDouble.of(1.5));

    assertEquals(AutoscalePolicy.CombinationMode.WEIGHTED, policy.combinationMode());
    assertEquals(3.0, policy.requestRateWeight().getAsDouble());
  }

  @Test
  void each_weight_must_be_positive_if_present() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AutoscalePolicy(
                1,
                5,
                50,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalInt.empty(),
                AutoscalePolicy.CombinationMode.WEIGHTED,
                OptionalDouble.of(0.0),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AutoscalePolicy(
                1,
                5,
                50,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalInt.empty(),
                AutoscalePolicy.CombinationMode.WEIGHTED,
                OptionalDouble.empty(),
                OptionalDouble.of(-1.0),
                OptionalDouble.empty(),
                OptionalDouble.empty()));
  }
}
