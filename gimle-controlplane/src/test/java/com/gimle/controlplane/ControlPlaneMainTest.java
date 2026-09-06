package com.gimle.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ControlPlaneMainTest {

  @Test
  void command_line_endpoint_wins_over_the_system_property() {
    assertEquals("cli:9093", ControlPlaneMain.resolveMuninnEndpoint("cli:9093", "prop:9093"));
  }

  @Test
  void system_property_supplies_the_endpoint_when_no_flag_was_given() {
    assertEquals("prop:9093", ControlPlaneMain.resolveMuninnEndpoint(null, "prop:9093"));
  }

  @Test
  void blank_command_line_endpoint_falls_back_to_the_system_property() {
    assertEquals("prop:9093", ControlPlaneMain.resolveMuninnEndpoint("  ", "prop:9093"));
  }

  @Test
  void unset_on_both_sides_ships_nowhere() {
    assertNull(ControlPlaneMain.resolveMuninnEndpoint(null, null));
    assertNull(ControlPlaneMain.resolveMuninnEndpoint("", " "));
  }
}
