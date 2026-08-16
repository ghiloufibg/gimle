package com.gimle.hilmir;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.doctor.DoctorCommand;
import com.gimle.hilmir.init.InitCommand;
import com.gimle.hilmir.launch.MachineLauncher;
import com.gimle.hilmir.launch.PkiInit;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.release.DeployCommand;
import com.gimle.hilmir.release.ReleaseStatusCommand;
import com.gimle.hilmir.release.ReleasesCommand;
import com.gimle.hilmir.release.RollbackCommand;
import com.gimle.hilmir.release.UndeployCommand;
import com.gimle.hilmir.release.UpgradeCommand;
import com.gimle.hilmir.store.StoreAddCommand;
import com.gimle.hilmir.store.StoreRemoveCommand;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.upgrade.UpgradeClusterCommand;
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
 *   hilmir store add &lt;peerId&gt; &lt;host&gt; &lt;raftPort&gt; &lt;clientPort&gt;
 *       (--topology &lt;file&gt; | --server &lt;host:clientPort&gt;[,...]) [--pki-dir &lt;dir&gt;]
 *   hilmir store remove &lt;peerId&gt;
 *       (--topology &lt;file&gt; | --server &lt;host:clientPort&gt;[,...]) [--pki-dir &lt;dir&gt;]
 *   hilmir upgrade-cluster -f &lt;topology.yaml&gt; --machine &lt;name&gt; --new-classpath &lt;cp&gt;
 *       [--new-java-executable &lt;path&gt;] [--data-root &lt;path&gt;] [--role &lt;ROLE&gt;]...
 *   hilmir deploy -f &lt;bundle.yaml&gt; [--values &lt;file&gt;] [--set k=v]... [--wait] [--dry-run] [-o json]
 *   hilmir upgrade -f &lt;bundle.yaml&gt; [--values &lt;file&gt;] [--set k=v]... [--wait] [--dry-run] [-o json]
 *   hilmir rollback --release &lt;name&gt; [--to-revision r] [--wait] [--dry-run] [-o json]
 *   hilmir undeploy --release &lt;name&gt; [--keep-tenants] [-o json]
 *   hilmir releases [-o json]
 *   hilmir release-status &lt;name&gt; [-o json]
 * </pre>
 *
 * {@code down}/{@code status} take {@code --data-root} rather than {@code -f}: the run ledger
 * {@code up} writes lives under a resolved runtime's own data root, and neither verb needs the
 * topology document again to find it (it defaults the same way {@link ResolvedRuntime#resolve}'s
 * own default does when omitted).
 *
 * <p>The six release verbs are a separate, Helm-equivalent concern layered on top of the same
 * dispatch shape: they talk to an already-running control plane over {@code --server host:port} (or
 * {@code GIMLE_SERVER}) rather than to a topology document, so their own flag parsing and HTTP
 * calling logic live entirely in {@code com.gimle.hilmir.release}, dispatched here the same way
 * every other verb is. {@code upgrade-cluster} is unrelated to {@code upgrade} despite the shared
 * word: {@code upgrade} rolls out a new revision of an already-deployed bundle (workloads, tenants,
 * config) against a running control plane's own API, while {@code upgrade-cluster} restarts one
 * machine's own platform binaries (store, muninn, andvari, fafnir, control plane) against a
 * topology document and a newly-unpacked classpath -- a platform-binary rollout, not a workload
 * one; see {@code com.gimle.hilmir.upgrade} for its own logic.
 *
 * <p>{@code doctor}/{@code init} are a third, independent concern: deployability diagnostics and
 * manifest scaffolding for a single built jar, needing neither a topology document nor a running
 * control plane (though {@code doctor --server} adds cluster-aware checks on top). Their own jar
 * and bytecode analysis lives in {@code com.gimle.hilmir.analyze}, shared by both.
 */
public final class HilmirMain {

  private static final String STORE_USAGE =
      """
      usage: hilmir store add <peerId> <host> <raftPort> <clientPort>
                 (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
                 [--pki-dir <dir>]
             hilmir store remove <peerId>
                 (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
                 [--pki-dir <dir>]""";

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
      case "store" -> handleStore(rest, out);
      case "upgrade-cluster" -> runUpgradeCluster(rest, out);
      case "deploy" -> runDeploy(rest, out);
      case "upgrade" -> runUpgrade(rest, out);
      case "rollback" -> runRollback(rest, out);
      case "undeploy" -> runUndeploy(rest, out);
      case "releases" -> runReleases(rest, out);
      case "release-status" -> runReleaseStatus(rest, out);
      case "doctor" -> runDoctor(rest, out);
      case "init" -> runInit(rest, out);
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

  private static int handleStore(final List<String> args, final PrintStream out) {
    if (args.isEmpty()) {
      throw new HilmirException(STORE_USAGE);
    }
    final String subVerb = args.get(0);
    final List<String> subArgs = args.subList(1, args.size());
    return switch (subVerb) {
      case "add" -> StoreAddCommand.run(subArgs, out);
      case "remove" -> StoreRemoveCommand.run(subArgs, out);
      default -> throw new HilmirException(STORE_USAGE);
    };
  }

  private static int runUpgradeCluster(final List<String> args, final PrintStream out) {
    return UpgradeClusterCommand.run(args, out);
  }

  private static int runDeploy(final List<String> args, final PrintStream out) {
    return DeployCommand.run(args, out);
  }

  private static int runUpgrade(final List<String> args, final PrintStream out) {
    return UpgradeCommand.run(args, out);
  }

  private static int runRollback(final List<String> args, final PrintStream out) {
    return RollbackCommand.run(args, out);
  }

  private static int runUndeploy(final List<String> args, final PrintStream out) {
    return UndeployCommand.run(args, out);
  }

  private static int runReleases(final List<String> args, final PrintStream out) {
    return ReleasesCommand.run(args, out);
  }

  private static int runReleaseStatus(final List<String> args, final PrintStream out) {
    return ReleaseStatusCommand.run(args, out);
  }

  private static int runDoctor(final List<String> args, final PrintStream out) {
    return DoctorCommand.run(args, out);
  }

  private static int runInit(final List<String> args, final PrintStream out) {
    return InitCommand.run(args, out);
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
          pki init -f <topology.yaml>
          store add <peerId> <host> <raftPort> <clientPort>
              (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
              [--pki-dir <dir>]
          store remove <peerId>
              (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
              [--pki-dir <dir>]
          upgrade-cluster -f <topology.yaml> --machine <name> --new-classpath <classpath-string>
              [--new-java-executable <path>] [--data-root <path>]
              [--role <STORE|CONTROL_PLANE|FAFNIR|MUNINN|ANDVARI>]...
              (platform binary rollout -- NOT the same as the "upgrade" bundle verb below)
          deploy -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
          upgrade -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
          rollback --release <name> [--to-revision r] [--wait] [--dry-run] [-o json]
          undeploy --release <name> [--keep-tenants] [-o json]
          releases [-o json]
          release-status <name> [-o json]
          doctor <jar> [<dep-jar>...] [--vessel] [--server host:port] [--tenant <id>] [-o json]
          init <jar> [--out-dir <dir>]""";
  }
}
