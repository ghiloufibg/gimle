package com.gimle.ragnarok.fenrir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.RagnarokException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Parsing and validation of chaos plan documents. */
final class ChaosPlanParserTest {

  private static FenrirPlan parse(final String yaml) {
    return ChaosPlanParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parses_a_plan_with_a_fixed_strike_gap_and_two_pools() {
    final FenrirPlan plan =
        parse(
            """
            seed: 42
            soakSeconds: 300
            strikeEverySeconds: 15
            eligibleDeployments: [burn-greeter-0, burn-greeter-1]
            convergeBetweenFaults: false
            gateTimeoutSeconds: 90
            pools:
              - kind: WORKER_KILL
                weight: 2
              - kind: STORE_BOUNCE
                dwellSeconds: 5
            """);
    assertEquals(42L, plan.seed());
    assertEquals(Duration.ofSeconds(300), plan.soak());
    assertEquals(Duration.ofSeconds(15), plan.gapMin());
    assertEquals(Duration.ofSeconds(15), plan.gapMax());
    assertEquals(2, plan.eligibleDeployments().size());
    assertEquals(false, plan.convergeBetweenFaults());
    assertEquals(Duration.ofSeconds(90), plan.gateTimeout());
    assertEquals(2, plan.pools().size());
    assertEquals(2, plan.pools().get(0).weight());
    assertEquals(Duration.ofSeconds(5), plan.pools().get(1).dwell());
  }

  @Test
  void parses_a_ranged_strike_gap() {
    final FenrirPlan plan =
        parse(
            """
            soakSeconds: 60
            strikeEveryMinSeconds: 5
            strikeEveryMaxSeconds: 20
            pools:
              - kind: LINK_CUT
            """);
    assertEquals(Duration.ofSeconds(5), plan.gapMin());
    assertEquals(Duration.ofSeconds(20), plan.gapMax());
  }

  @Test
  void a_plan_with_no_pools_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("soakSeconds: 60\n"));
  }

  @Test
  void an_unknown_fault_kind_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () -> parse("soakSeconds: 60\npools:\n  - kind: NOT_A_FAULT_KIND\n"));
  }

  @Test
  void a_worker_kill_pool_without_eligible_deployments_is_rejected() {
    assertThrows(
        RagnarokException.class, () -> parse("soakSeconds: 60\npools:\n  - kind: WORKER_KILL\n"));
  }

  @Test
  void a_pool_with_both_dwell_and_heal_after_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                soakSeconds: 60
                pools:
                  - kind: LINK_CUT
                    dwellSeconds: 5
                    healAfterSeconds: 5
                """));
  }

  @Test
  void malformed_yaml_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("not: [valid"));
  }

  @Test
  void a_non_mapping_root_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("- just\n- a\n- list\n"));
  }

  @Test
  void the_seed_system_property_still_overrides_a_parsed_plan() {
    System.setProperty(FenrirPlan.SEED_PROPERTY, "99");
    try {
      final FenrirPlan plan = parse("seed: 1\nsoakSeconds: 60\npools:\n  - kind: LINK_CUT\n");
      assertEquals(99L, plan.seed());
    } finally {
      System.clearProperty(FenrirPlan.SEED_PROPERTY);
    }
  }

  @Test
  void the_bundled_smoke_plan_would_resolve_if_one_existed() {
    // No bundled chaos plan ships today (unlike Surtr's module-density workload) -- this test
    // documents that resolve() still throws a clear RagnarokException rather than a raw
    // NullPointerException when asked for one that doesn't exist.
    assertTrue(
        assertThrows(RagnarokException.class, () -> ChaosPlanParser.resolve("no-such-plan"))
            .getMessage()
            .contains("no chaos plan resource"));
  }
}
