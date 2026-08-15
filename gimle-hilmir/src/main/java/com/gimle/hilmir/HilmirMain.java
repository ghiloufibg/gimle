package com.gimle.hilmir;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.launch.MachineLauncher;
import com.gimle.hilmir.launch.PkiInit;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.validate.Finding;
import com.gimle.hilmir.validate.Severity;
import com.gimle.hilmir.validate.TopologyValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Hilmir's entry point and verb dispatch, matching {@code gimle-cli}'s own hand-rolled shape (no
 * picocli):
 *
 * <pre>
 *   hilmir validate -f &lt;topology.yaml&gt;
 *   hilmir plan -f &lt;topology.yaml&gt; [--machine &lt;name&gt;]
 *   hilmir up -f &lt;topology.yaml&gt; --machine &lt;name&gt;
 *   hilmir down --machine &lt;name&gt; [--data-root &lt;path&gt;]
 *   hilmir status --machine &lt;name&gt; [--data-root &lt;path&gt;]
 *   hilmir pki init -f &lt;topology.yaml&gt;
 * </pre>
 *
 * {@code down}/{@code status} take {@code --data-root} rather than {@code -f}: the run ledger
 * {@code up} writes lives under a resolved runtime's own data root, and neither verb needs the
 * topology document again to find it (it defaults the same way {@link ResolvedRuntime#resolve}'s
 * own default does when omitted).
 */
public final class HilmirMain {

  private HilmirMain() {}

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
    } catch (final HilmirException | GimleManifestException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
  }

  private static int dispatch(final String[] args, final PrintStream out) {
    if (args.length == 0) {
      throw new HilmirException(usage());
    }
    final String verb = args[0];
    final List<String> rest = List.of(args).subList(1, args.length);
    return switch (verb) {
      case "validate" -> runValidate(rest, out);
      case "plan" -> runPlan(rest, out);
      case "up" -> runUp(rest, out);
      case "down" -> runDown(rest, out);
      case "status" -> runStatus(rest, out);
      case "pki" -> handlePki(rest, out);
      default -> throw new HilmirException(usage());
    };
  }

  private static int runValidate(final List<String> args, final PrintStream out) {
    final Topology topology = parseFile(requireFileFlag(args));
    final List<Finding> findings = TopologyValidator.validate(topology);
    printFindings(findings, out);
    return hasError(findings) ? 1 : 0;
  }

  private static int runPlan(final List<String> args, final PrintStream out) {
    final Topology topology = parseFile(requireFileFlag(args));
    final List<Finding> findings = TopologyValidator.validate(topology);
    if (hasError(findings)) {
      printFindings(findings, out);
      return 1;
    }
    final ResolvedRuntime runtime = resolveRuntime(topology);
    final ClusterPlan plan = LaunchPlanner.plan(topology, runtime);
    printPlan(plan, machineFlag(args), out);
    return 0;
  }

  private static int runUp(final List<String> args, final PrintStream out) {
    final Topology topology = parseFile(requireFileFlag(args));
    final List<Finding> findings = TopologyValidator.validate(topology);
    if (hasError(findings)) {
      printFindings(findings, out);
      return 1;
    }
    final String machine = requireMachineFlag(args);
    final ResolvedRuntime runtime = resolveRuntime(topology);
    final List<RunRecord> records = MachineLauncher.up(topology, machine, runtime, out);
    out.println("started " + records.size() + " process(es) on machine " + machine);
    return 0;
  }

  private static int runDown(final List<String> args, final PrintStream out) {
    requireMachineFlag(args);
    MachineLauncher.down(dataRootFlag(args), out);
    return 0;
  }

  private static int runStatus(final List<String> args, final PrintStream out) {
    requireMachineFlag(args);
    MachineLauncher.status(dataRootFlag(args), out);
    return 0;
  }

  private static int handlePki(final List<String> args, final PrintStream out) {
    if (args.isEmpty() || !args.get(0).equals("init")) {
      throw new HilmirException("usage: hilmir pki init -f <topology.yaml>");
    }
    final List<String> initArgs = args.subList(1, args.size());
    final Topology topology = parseFile(requireFileFlag(initArgs));
    final ResolvedRuntime runtime = resolveRuntime(topology);
    PkiInit.run(topology, runtime, out);
    return 0;
  }

  private static ResolvedRuntime resolveRuntime(final Topology topology) {
    return ResolvedRuntime.resolve(
        topology.runtime(), "java", System.getProperty("java.class.path"), Path.of("gimle-data"));
  }

  private static boolean hasError(final List<Finding> findings) {
    return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
  }

  /**
   * Errors first, then warnings; stable within each group, so encounter order (rule-catalog order,
   * see {@code TopologyValidator}) survives the split.
   */
  private static void printFindings(final List<Finding> findings, final PrintStream out) {
    findings.stream()
        .sorted(Comparator.comparing(f -> f.severity() == Severity.ERROR ? 0 : 1))
        .forEach(f -> out.println("[" + f.severity() + "] " + f.code() + ": " + f.message()));
  }

  private static void printPlan(
      final ClusterPlan plan, final Optional<String> machineFilter, final PrintStream out) {
    for (final MachinePlan machinePlan : plan.byMachine().values()) {
      if (machineFilter.isPresent() && !machineFilter.get().equals(machinePlan.machine())) {
        continue;
      }
      out.println("machine: " + machinePlan.machine());
      for (final ProcessCommand command : machinePlan.commands()) {
        out.println("  " + command.role() + " " + command.id());
        for (final String arg : command.command()) {
          out.println("    " + arg);
        }
      }
    }
  }

  private static Topology parseFile(final Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return TopologyParser.parse(in);
    } catch (final IOException e) {
      throw new HilmirException("failed reading topology file " + file + ": " + e.getMessage(), e);
    }
  }

  private static Path requireFileFlag(final List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals("-f") && i + 1 < args.size()) {
        return Path.of(args.get(i + 1));
      }
    }
    throw new HilmirException("missing required flag: -f <topology.yaml>");
  }

  private static Optional<String> machineFlag(final List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals("--machine") && i + 1 < args.size()) {
        return Optional.of(args.get(i + 1));
      }
    }
    return Optional.empty();
  }

  private static String requireMachineFlag(final List<String> args) {
    return machineFlag(args)
        .orElseThrow(() -> new HilmirException("missing required flag: --machine <name>"));
  }

  private static Path dataRootFlag(final List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals("--data-root") && i + 1 < args.size()) {
        return Path.of(args.get(i + 1));
      }
    }
    return Path.of("gimle-data");
  }

  private static String usage() {
    return """
        usage: hilmir <verb> [args]

        verbs:
          validate -f <topology.yaml>
          plan -f <topology.yaml> [--machine <name>]
          up -f <topology.yaml> --machine <name>
          down --machine <name> [--data-root <path>]
          status --machine <name> [--data-root <path>]
          pki init -f <topology.yaml>""";
  }
}
