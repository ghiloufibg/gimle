package com.gimle.core.banner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml) --
// the same convention TransportProtocolTest/LoginThrottleTest already establish.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BannerPrinterTest {

  private static final String COLOR_PROPERTY = "gimle.banner.color";
  private static final String ENABLED_PROPERTY = "gimle.banner.enabled";

  @AfterEach
  void clearProperties() {
    System.clearProperty(COLOR_PROPERTY);
    System.clearProperty(ENABLED_PROPERTY);
  }

  @Test
  void render_substitutes_every_caller_supplied_placeholder() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered =
        BannerPrinter.render(
            Map.of(
                "app.name", "Test App",
                "app.description", "does testy things",
                "app.version", "9.9.9"));

    assertTrue(rendered.contains("Test App"), "expected app.name substituted: " + rendered);
    assertTrue(
        rendered.contains("does testy things"),
        "expected app.description substituted: " + rendered);
    assertTrue(rendered.contains("9.9.9"), "expected app.version substituted: " + rendered);
  }

  @Test
  void render_falls_back_to_defaults_when_the_caller_supplies_nothing() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered = BannerPrinter.render(Map.of());

    assertTrue(rendered.contains("Application"), "expected default app.name: " + rendered);
    assertTrue(rendered.contains("0.0.0"), "expected default app.version: " + rendered);
  }

  @Test
  void render_leaves_no_dollar_brace_placeholder_unresolved() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered = BannerPrinter.render(Map.of("app.name", "Whatever"));

    assertFalse(
        rendered.contains("${"),
        "every ${...} placeholder should have been substituted or dropped");
  }

  @Test
  void color_never_strips_every_ansi_escape() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered = BannerPrinter.render(Map.of());
    assertFalse(rendered.contains("["), "expected no ANSI escapes with color=never");
  }

  @Test
  void color_always_includes_ansi_escapes() {
    System.setProperty(COLOR_PROPERTY, "always");
    String rendered = BannerPrinter.render(Map.of());
    assertTrue(rendered.contains("["), "expected ANSI escapes with color=always");
  }

  @Test
  void color_override_takes_precedence_regardless_of_environment() {
    System.setProperty(COLOR_PROPERTY, "always");
    assertEquals(BannerPrinter.ColorMode.EXTENDED, BannerPrinter.detectColorMode());

    System.setProperty(COLOR_PROPERTY, "never");
    assertEquals(BannerPrinter.ColorMode.NONE, BannerPrinter.detectColorMode());
  }

  @Test
  void print_writes_the_rendered_banner_to_the_given_stream() {
    System.setProperty(COLOR_PROPERTY, "never");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    BannerPrinter.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("X"));
  }

  @Test
  void print_is_a_no_op_when_disabled() {
    System.setProperty(ENABLED_PROPERTY, "false");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    BannerPrinter.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertEquals(0, buffer.size(), "expected nothing written when gimle.banner.enabled=false");
  }

  @Test
  void print_is_enabled_by_default() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    BannerPrinter.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertTrue(buffer.size() > 0, "expected the banner to print when the property is unset");
  }
}
