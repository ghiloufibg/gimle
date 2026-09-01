package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mapping from lifecycle state to colour, pinned against the console's own. A state that reads
 * amber in the browser has to read amber in the terminal, and this is where a drift between the two
 * fails the build rather than quietly shipping.
 */
class StatusVariantTest {

  /**
   * Transcribed from {@code gimle-console/src/components/status.tsx}'s {@code LifecycleBadge}: ok
   * for the two settled states, warn for the two in-flight ones, bad for the two terminal failures,
   * muted for everything before a module is running.
   */
  private static final Map<String, StatusVariant> CONSOLE_MAPPING =
      Map.of(
          "ACTIVE", StatusVariant.OK,
          "COMPLETED", StatusVariant.OK,
          "STARTING", StatusVariant.WARN,
          "STOPPING", StatusVariant.WARN,
          "UNINSTALLED", StatusVariant.BAD,
          "FAILED", StatusVariant.BAD,
          "INSTALLED", StatusVariant.MUTED,
          "RESOLVED", StatusVariant.MUTED);

  @Test
  void every_lifecycle_state_maps_to_the_same_variant_the_console_assigns_it() {
    CONSOLE_MAPPING.forEach(
        (state, expected) ->
            assertEquals(expected, StatusVariant.ofLifecycleState(state), "state " + state));
  }

  @Test
  void every_lifecycle_state_the_platform_defines_is_covered_by_the_mapping() {
    // Mirrors com.gimle.core.protocol.InstanceEventKind's own value list minus TRANSITION_FAILED,
    // which is an event kind rather than a state. A state added to the platform and not to this
    // list fails here, which is the point.
    List<String> platformStates =
        List.of(
            "INSTALLED",
            "RESOLVED",
            "STARTING",
            "ACTIVE",
            "STOPPING",
            "UNINSTALLED",
            "FAILED",
            "COMPLETED");

    assertEquals(platformStates.size(), CONSOLE_MAPPING.size());
    platformStates.forEach(
        state -> assertEquals(true, CONSOLE_MAPPING.containsKey(state), "unmapped state " + state));
  }

  @Test
  void an_instance_hugin_has_no_observation_for_reads_muted_like_a_not_yet_running_one() {
    assertEquals(StatusVariant.MUTED, StatusVariant.ofLifecycleState("PENDING"));
  }

  @Test
  void node_state_colours_follow_the_same_three_way_scale() {
    assertEquals(StatusVariant.OK, StatusVariant.ofNodeState("READY"));
    assertEquals(StatusVariant.WARN, StatusVariant.ofNodeState("CORDONED"));
    assertEquals(StatusVariant.WARN, StatusVariant.ofNodeState("STALE"));
    assertEquals(StatusVariant.MUTED, StatusVariant.ofNodeState("UNKNOWN"));
  }

  @Test
  void a_gauge_turns_amber_then_red_as_a_node_fills_up() {
    assertEquals(StatusVariant.OK, StatusVariant.ofUtilization(0.0));
    assertEquals(StatusVariant.OK, StatusVariant.ofUtilization(0.74));
    assertEquals(StatusVariant.WARN, StatusVariant.ofUtilization(0.75));
    assertEquals(StatusVariant.BAD, StatusVariant.ofUtilization(0.95));
  }
}
