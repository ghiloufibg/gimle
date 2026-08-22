package com.gimle.core.banner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml) --
// the same convention TransportProtocolTest/LoginThrottleTest already establish.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GimleBannerTest {

  private static final String COLOR_PROPERTY = "gimle.color";
  private static final String ENABLED_PROPERTY = "gimle.banner.enabled";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");

  @AfterEach
  void clearProperties() {
    System.clearProperty(COLOR_PROPERTY);
    System.clearProperty(ENABLED_PROPERTY);
  }

  @Test
  void render_substitutes_every_caller_supplied_placeholder() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered =
        GimleBanner.render(
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
    String rendered = GimleBanner.render(Map.of());

    assertTrue(rendered.contains("Application"), "expected default app.name: " + rendered);
    assertTrue(rendered.contains("0.0.0"), "expected default app.version: " + rendered);
  }

  @Test
  void render_leaves_no_dollar_brace_placeholder_unresolved() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered = GimleBanner.render(Map.of("app.name", "Whatever"));

    assertFalse(
        rendered.contains("${"),
        "every ${...} placeholder should have been substituted or dropped");
  }

  @Test
  void color_never_strips_every_ansi_escape() {
    System.setProperty(COLOR_PROPERTY, "never");
    String rendered = GimleBanner.render(Map.of());
    assertFalse(rendered.contains("["), "expected no ANSI escapes with color=never");
  }

  @Test
  void color_always_includes_ansi_escapes() {
    System.setProperty(COLOR_PROPERTY, "always");
    String rendered = GimleBanner.render(Map.of());
    assertTrue(rendered.contains("["), "expected ANSI escapes with color=always");
  }

  @Test
  void print_writes_the_rendered_banner_to_the_given_stream() {
    System.setProperty(COLOR_PROPERTY, "never");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    GimleBanner.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("X"));
  }

  @Test
  void print_is_a_no_op_when_disabled() {
    System.setProperty(ENABLED_PROPERTY, "false");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    GimleBanner.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertEquals(0, buffer.size(), "expected nothing written when gimle.banner.enabled=false");
  }

  @Test
  void print_is_enabled_by_default() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    GimleBanner.print(
        new PrintStream(buffer, true, StandardCharsets.UTF_8), Map.of("app.name", "X"));

    assertTrue(buffer.size() > 0, "expected the banner to print when the property is unset");
  }

  // Every line that carries the wordmark (marked by the ${C_GOLD_B} placeholder that colors it)
  // must render to the same visible width as every other such line -- the pictogram and the
  // block-letter wordmark sit side by side on each row, so one short row throws the whole banner
  // out of alignment. This is a template-content invariant, not a rendering one: it is checked
  // against the raw resource text (placeholders present, unresolved) rather than through
  // GimleBanner.render, so it fails the same way regardless of color mode. The caller-supplied
  // byline (${app.name} etc.) also colors itself with ${C_GOLD_B} but is free-text of whatever
  // length the caller passes, not part of the fixed-width pictogram block, so it's excluded here.
  @Test
  void wordmark_rows_are_the_same_width_in_the_unicode_banner() {
    assertWordmarkRowsAlign("banner.txt");
  }

  @Test
  void wordmark_rows_are_the_same_width_in_the_ascii_banner() {
    assertWordmarkRowsAlign("banner-ascii.txt");
  }

  private static void assertWordmarkRowsAlign(String resource) {
    List<String> wordmarkRows =
        loadResourceLines(resource).stream()
            .filter(line -> line.contains("${C_GOLD_B}") && !line.contains("${app."))
            .map(line -> PLACEHOLDER.matcher(line).replaceAll(""))
            .toList();

    assertFalse(wordmarkRows.isEmpty(), "expected at least one wordmark row in " + resource);
    int expectedWidth = wordmarkRows.get(0).length();
    for (String row : wordmarkRows) {
      assertEquals(
          expectedWidth,
          row.length(),
          () -> "wordmark row width mismatch in " + resource + " -- misaligned row: " + row);
    }
  }

  private static List<String> loadResourceLines(String resource) {
    try (InputStream in = GimleBanner.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
