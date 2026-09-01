package com.gimle.cli.spi;

import java.io.PrintStream;
import java.util.List;

/**
 * A verb the CLI does not itself implement, contributed by another jar on the path and discovered
 * through {@link java.util.ServiceLoader}. The lookup happens immediately before {@code GimleCli}'s
 * own unknown-verb error, so a verb with no provider on the path still produces exactly that error.
 *
 * <p>An implementation is handed a {@link ClusterReader}, never the CLI's own HTTP client, so an
 * extension can read cluster state and nothing else.
 *
 * <p>A provider must be declared twice to be found from both launch paths: a {@code
 * META-INF/services/com.gimle.cli.spi.CliExtension} resource (what resolves it from the classpath,
 * which is how the shipped {@code bin/gimle} script and every test load this code) and a {@code
 * provides ... with ...} directive in the provider's own {@code module-info} (what would resolve it
 * on the module path). A classpath-only declaration works today; the module-path one keeps the
 * module graph self-describing.
 */
public interface CliExtension {

  /** The verb this extension implements, e.g. {@code "top"}. */
  String verb();

  /** One line, folded into {@code gimle --help} under the built-in verbs. */
  String usageLine();

  /**
   * Runs the verb. Everything after the verb itself arrives in {@code args}. A failure the operator
   * should read as a message rather than a stack trace is reported by throwing {@code
   * com.gimle.cli.CliException}, which the CLI's own top-level handler already turns into an {@code
   * error: ...} line and a non-zero exit code.
   */
  void run(List<String> args, ClusterReader reader, PrintStream out);
}
