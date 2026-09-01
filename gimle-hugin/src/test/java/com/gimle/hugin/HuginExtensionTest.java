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
  void an_argument_is_rejected_with_usage_rather_than_ignored() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CliException thrown =
        assertThrows(
            CliException.class,
            () ->
                new HuginExtension()
                    .run(
                        List.of("--interval=5s"),
                        new UnusedReader(),
                        new PrintStream(buffer, true, StandardCharsets.UTF_8)));

    assertTrue(thrown.getMessage().contains("usage: gimle top"), thrown.getMessage());
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
