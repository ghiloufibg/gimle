package com.gimle.ragnarok;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.exception.GimleTlsException;
import com.gimle.ragnarok.cli.ChaosCommand;
import com.gimle.ragnarok.cli.PreflightCommand;
import com.gimle.ragnarok.cli.ReplayCommand;
import com.gimle.ragnarok.cli.ReportCommand;
import com.gimle.ragnarok.cli.StressCommand;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Ragnarök's entry point and verb dispatch, matching {@code gimle-hilmir}'s own hand-rolled shape
 * (no picocli):
 *
 * <pre>
 *   ragnarok preflight --target &lt;target.yaml&gt;
 *   ragnarok chaos --target &lt;target.yaml&gt; --plan &lt;plan.yaml&gt; [--seed N]
 *       [--confirm-destructive] [--report &lt;dir&gt;]
 *   ragnarok stress --target &lt;target.yaml&gt; [--workload &lt;name-or-path&gt;]
 *       [--module-jar &lt;path&gt;] [--report &lt;dir&gt;]
 *   ragnarok replay --from-report &lt;chaos-report.json&gt; --target &lt;target.yaml&gt;
 *       [--confirm-destructive] [--report &lt;dir&gt;]
 *   ragnarok report (--chaos-report &lt;path&gt; | --surtr-report &lt;dir&gt;)
 * </pre>
 *
 * {@code preflight}/{@code chaos}/{@code stress}/{@code replay} each open their own {@link
 * com.gimle.ragnarok.target.endpoint.EndpointClusterTarget} from a {@code --target} YAML document
 * ({@link com.gimle.ragnarok.target.endpoint.TargetSpecParser}) and run Fenrir/Surtr against a
 * real, already-running cluster -- no boot-time cluster interposition, no process control, the same
 * honest degradation {@code EndpointClusterTarget} itself documents. {@code chaos} carries the
 * tool's one safety catch: a plan naming a destructive fault kind (anything beyond a pure network
 * fault) needs {@code --confirm-destructive}, and {@code replay} is subject to the identical gate.
 */
public final class RagnarokMain {

  private RagnarokMain() {}

  public static void main(final String[] args) {
    final int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /** Testable entry point: returns an exit code instead of calling {@code System.exit}. */
  public static int run(final String[] args, final PrintStream out, final PrintStream err) {
    try {
      return dispatch(args, out);
    } catch (final RagnarokException
        | GimleTlsException
        | GimleManifestException
        | UncheckedIOException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
  }

  private static int dispatch(final String[] args, final PrintStream out) {
    if (args.length == 0) {
      out.println(usage());
      return 1;
    }
    final String verb = args[0];
    final List<String> rest = List.of(args).subList(1, args.length);
    return switch (verb) {
      case "preflight" -> PreflightCommand.run(rest, out);
      case "chaos" -> ChaosCommand.run(rest, out);
      case "stress" -> StressCommand.run(rest, out);
      case "replay" -> ReplayCommand.run(rest, out);
      case "report" -> ReportCommand.run(rest, out);
      case "-h", "--help" -> {
        out.println(usage());
        yield 0;
      }
      default -> throw new RagnarokException(usage());
    };
  }

  private static String usage() {
    return """
        usage: ragnarok <verb> [args]

        verbs:
          preflight --target <target.yaml>
          chaos --target <target.yaml> --plan <plan.yaml> [--seed N]
              [--confirm-destructive] [--report <dir>]
          stress --target <target.yaml> [--workload <name-or-path>]
              [--module-jar <path>] [--report <dir>]
          replay --from-report <chaos-report.json> --target <target.yaml>
              [--confirm-destructive] [--report <dir>]
          report (--chaos-report <path> | --surtr-report <dir>)""";
  }
}
