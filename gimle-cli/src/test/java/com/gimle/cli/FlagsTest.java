package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlagsTest {

  private static final String USAGE = "usage: gimle widget set <name> --value <v>";

  @Test
  void a_valued_flag_and_a_boolean_flag_both_parse() {
    Flags flags = Flags.parse(List.of("--value", "42", "--force"), Set.of("--force"), USAGE);

    assertEquals("42", flags.get("--value"));
    assertTrue(flags.isSet("--force"));
  }

  @Test
  void a_repeatable_flag_accumulates_every_occurrence() {
    Flags flags =
        Flags.parse(List.of("--tag", "a", "--tag", "b"), Set.of(), Set.of("--tag"), USAGE);

    assertEquals(List.of("a", "b"), flags.getAll("--tag"));
  }

  @Test
  void a_stray_non_flag_token_reports_the_callers_own_usage_string() {
    CliException e =
        assertThrows(
            CliException.class, () -> Flags.parse(List.of("stray-value"), Set.of(), USAGE));

    assertTrue(e.getMessage().contains("unexpected argument: stray-value"));
    assertTrue(
        e.getMessage().contains(USAGE),
        "a stray positional argument -- the easiest mistake to make when a flag was expected --"
            + " must still point the caller at the correct syntax, the same way every"
            + " too-few-arguments check elsewhere in this package already does");
  }

  @Test
  void a_flag_missing_its_value_also_reports_the_callers_own_usage_string() {
    CliException e =
        assertThrows(CliException.class, () -> Flags.parse(List.of("--value"), Set.of(), USAGE));

    assertTrue(e.getMessage().contains("--value requires a value"));
    assertTrue(e.getMessage().contains(USAGE));
  }

  @Test
  void an_unset_boolean_flag_is_not_reported_as_set() {
    Flags flags = Flags.parse(List.of(), Set.of("--force"), USAGE);

    assertFalse(flags.isSet("--force"));
  }
}
