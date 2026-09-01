package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * {@link AutoscalePolicy.CombinationMode}/per-signal-weight/stabilization-window validation and
 * defaulting -- the four target-signal validations these tests don't re-cover are already pinned by
 * the codebase's existing manifest-level tests ({@code DeploymentManifestParserTest}).
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

  @Test
  void every_pre_cooldown_constructor_takes_the_documented_default_windows() {
    AutoscalePolicy threeArg = new AutoscalePolicy(1, 5, 50);
    AutoscalePolicy elevenArg =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            AutoscalePolicy.CombinationMode.WEIGHTED,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty());

    assertEquals(Duration.ZERO, threeArg.scaleUpCooldown());
    assertEquals(Duration.ofMinutes(5), threeArg.scaleDownCooldown());
    assertEquals(AutoscalePolicy.DEFAULT_SCALE_UP_COOLDOWN, elevenArg.scaleUpCooldown());
    assertEquals(AutoscalePolicy.DEFAULT_SCALE_DOWN_COOLDOWN, elevenArg.scaleDownCooldown());
  }

  @Test
  void a_null_cooldown_defaults_the_same_way_a_null_combination_mode_does() {
    AutoscalePolicy policy = cooldowns(null, null);

    assertEquals(AutoscalePolicy.DEFAULT_SCALE_UP_COOLDOWN, policy.scaleUpCooldown());
    assertEquals(AutoscalePolicy.DEFAULT_SCALE_DOWN_COOLDOWN, policy.scaleDownCooldown());
  }

  @Test
  void a_zero_cooldown_is_kept_as_written_rather_than_defaulted() {
    AutoscalePolicy policy = cooldowns(Duration.ZERO, Duration.ZERO);

    assertEquals(Duration.ZERO, policy.scaleUpCooldown());
    assertEquals(Duration.ZERO, policy.scaleDownCooldown());
  }

  @Test
  void a_negative_cooldown_is_rejected_in_either_direction() {
    assertThrows(
        IllegalArgumentException.class, () -> cooldowns(Duration.ofSeconds(-1), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> cooldowns(Duration.ZERO, Duration.ofMinutes(-5)));
  }

  private static AutoscalePolicy cooldowns(Duration scaleUp, Duration scaleDown) {
    return new AutoscalePolicy(
        1,
        5,
        50,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalInt.empty(),
        AutoscalePolicy.CombinationMode.WORST_SIGNAL,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        scaleUp,
        scaleDown);
  }
}
