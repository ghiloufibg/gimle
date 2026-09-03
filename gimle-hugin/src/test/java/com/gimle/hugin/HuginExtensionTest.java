package com.gimle.hugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import com.gimle.cli.spi.CliExtension;
import com.gimle.cli.spi.ClusterReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * The provider side of the seam. The discovery test is the one that matters: it loads from the
 * classpath, as an unnamed module, exactly as the shipped {@code bin/gimle} does -- so a services
 * declaration that only worked on the module path fails here rather than in someone's terminal.
 */
class HuginExtensionTest {

  @Test
  void the_provider_is_discoverable_from_the_classpath() {
    List<CliExtension> discovered =
        ServiceLoader.load(CliExtension.class, HuginExtension.class.getClassLoader()).stream()
            .map(ServiceLoader.Provider::get)
            .toList();

    assertTrue(
        discovered.stream().anyMatch(extension -> extension instanceof HuginExtension),
        "no HuginExtension among " + discovered);
  }

  @Test
  void this_test_runs_unnamed_which_is_what_makes_the_discovery_test_meaningful() {
    assertTrue(
        !HuginExtension.class.getModule().isNamed(),
        "these tests must run on the classpath for the services-file check above to mean anything");
  }

  @Test
  void the_verb_is_top_and_it_documents_itself_in_one_line() {
    HuginExtension extension = new HuginExtension();

    assertEquals("top", extension.verb());
    assertTrue(extension.usageLine().startsWith("top"), extension.usageLine());
    assertTrue(extension.usageLine().contains("read-only"), extension.usageLine());
  }

  @Test
  void an_unknown_argument_is_rejected_with_usage_rather_than_ignored() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CliException thrown =
        assertThrows(
            CliException.class,
            () ->
                new HuginExtension()
                    .run(
                        List.of("--follow"),
                        new UnusedReader(),
                        new PrintStream(buffer, true, StandardCharsets.UTF_8)));

    assertTrue(thrown.getMessage().contains("usage: gimle top"), thrown.getMessage());
  }

  @Test
  void a_refresh_interval_is_accepted_and_defaults_to_two_seconds() {
    assertEquals(Duration.ofSeconds(2), HuginExtension.parseInterval(List.of()));
    assertEquals(Duration.ofSeconds(10), HuginExtension.parseInterval(List.of("--interval=10")));
  }

  @Test
  void an_interval_that_is_not_a_number_says_so_rather_than_printing_usage() {
    // A well-formed flag carrying a bad value has a specific thing wrong with it, and saying which
    // is more use than restating the whole verb's usage.
    CliException thrown =
        assertThrows(
            CliException.class, () -> HuginExtension.parseInterval(List.of("--interval=5s")));

    assertTrue(thrown.getMessage().contains("whole number of seconds"), thrown.getMessage());
  }

  @Test
  void an_interval_outside_the_useful_range_is_rejected_at_both_ends() {
    for (String out : List.of("--interval=0", "--interval=61")) {
      CliException thrown =
          assertThrows(CliException.class, () -> HuginExtension.parseInterval(List.of(out)));
      assertTrue(thrown.getMessage().contains("between 1 and 60"), thrown.getMessage());
    }
  }

  @Test
  void the_heavier_screens_never_poll_at_the_cluster_views_own_two_second_tick() {
    // Services costs one request per Service and the audit trail only grows as fast as people
    // change things; both behind a floor so turning the cluster tick down cannot make either
    // spend requests on answers that have not changed.
    RefreshIntervals fast = RefreshIntervals.from(Duration.ofSeconds(1));
    assertEquals(Duration.ofSeconds(1), fast.cluster());
    assertEquals(Duration.ofSeconds(5), fast.services());
    assertEquals(Duration.ofSeconds(5), fast.activity());

    // Turning it up, though, applies everywhere: an operator asking for less traffic gets it.
    RefreshIntervals slow = RefreshIntervals.from(Duration.ofSeconds(30));
    assertEquals(Duration.ofSeconds(30), slow.cluster());
    assertEquals(Duration.ofSeconds(30), slow.activity());
  }

  /** Never called: the argument check happens before anything touches the control plane. */
  private static final class UnusedReader implements ClusterReader {

    @Override
    public List<Map<String, Object>> getList(final String path) {
      throw new AssertionError("an extension must not read anything before validating its args");
    }

    @Override
    public Map<String, Object> getObject(final String path) {
      throw new AssertionError("an extension must not read anything before validating its args");
    }

    @Override
    public InputStream openStream(final String path) {
      throw new AssertionError("an extension must not read anything before validating its args");
    }

    @Override
    public String serverAddress() {
      return "localhost:8080";
    }
  }
}
