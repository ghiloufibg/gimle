package com.gimle.hilmir.upgrade;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.launch.MachineLauncher;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.validate.CheckedTopology;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code hilmir upgrade-cluster -f <topology.yaml> --machine <name> --new-classpath <cp>
 * [--new-java-executable <path>] [--data-root <path>] [--role <ROLE>]...}: a per-machine platform
 * binary rollout, restarting one machine's own stateless platform processes -- store, muninn,
 * andvari, fafnir, control plane -- against a newly-unpacked classpath, one role at a time.
 *
 * <p>This is deliberately NOT the same thing as {@code hilmir upgrade}: that verb rolls out a new
 * revision of a deployed <em>bundle</em> (workloads/tenants/config) to an already-running control
 * plane over its HTTP API; this one restarts the platform's own binaries underneath a topology this
 * process has direct OS control over. Neither verb can substitute for the other.
 *
 * <p>This command itself is strictly per-machine, exactly like {@code up}/{@code down}/{@code
 * status}'s own local form -- a multi-machine rollout is {@code --remote} (see {@code
 * com.gimle.hilmir.remote.RemoteDispatch#upgradeCluster}) re-invoking this exact command once per
 * machine over SSH, in the platform's own fixed boot order, the same external-orchestration shape
 * {@code --remote up}/{@code down}/{@code status} already established; this class itself never
 * gains any cross-machine logic of its own.
 *
 * <p>{@code --role} is restricted to the five stateless platform process kinds; {@code AGENT} (and
 * {@code WORKER}, which is never spawned as its own process to begin with) is rejected outright --
 * an agent's own jar only matters at its next natural restart, and restarting one risks
 * interrupting in-flight worker instances, a materially different blast radius than a stateless
 * platform binary bump.
 */
public final class UpgradeClusterCommand {

  private static final Set<ProcessRole> RESTARTABLE_ROLES =
      EnumSet.of(
          ProcessRole.STORE,
          ProcessRole.MUNINN,
          ProcessRole.ANDVARI,
          ProcessRole.FAFNIR,
          ProcessRole.CONTROL_PLANE);

  private static final String USAGE =
      """
      usage: hilmir upgrade-cluster -f <topology.yaml> --machine <name>
          --new-classpath <classpath-string>
          [--new-java-executable <path>] [--data-root <path>]
          [--role <STORE|CONTROL_PLANE|FAFNIR|MUNINN|ANDVARI>]...""";

  private UpgradeClusterCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    return run(args, out, MachineLauncher::restartRole);
  }

  /**
   * Package-visible so a test can inject a stubbed {@link RoleRestarter} in place of a real one.
   */
  static int run(final List<String> args, final PrintStream out, final RoleRestarter restarter) {
    final CheckedTopology checked = CheckedTopology.check(requireFileFlag(args));
    if (checked.hasError()) {
      checked
          .findings()
          .forEach(f -> out.println("[" + f.severity() + "] " + f.code() + ": " + f.message()));
      return 1;
    }
    final Topology topology = checked.require();

    final String machine = requireFlag(args, "--machine");
    final String newClasspath = requireFlag(args, "--new-classpath");
    final String newJavaExecutable =
        optionalFlag(args, "--new-java-executable")
            .orElseGet(() -> topology.runtime().javaExecutable().orElse("java"));
    final Path dataRoot =
        optionalFlag(args, "--data-root")
            .map(Path::of)
            .orElseGet(() -> topology.runtime().dataRoot().orElse(Path.of("gimle-data")));
    final ResolvedRuntime newRuntime =
        new ResolvedRuntime(newJavaExecutable, newClasspath, dataRoot);

    final List<ProcessRole> requestedRoles = parseRoles(allValues(args, "--role"));

    final ClusterPlan plan = LaunchPlanner.plan(topology, newRuntime);
    final MachinePlan machinePlan = plan.requireMachine(machine);
    final List<ProcessRole> rolesToRestart = resolveRoles(machinePlan, machine, requestedRoles);

    final List<ProcessRole> restarted = new ArrayList<>();
    for (final ProcessRole role : rolesToRestart) {
      out.println("restarting " + role + " on machine " + machine + "...");
      final RunRecord record = restarter.restart(topology, machine, role, newRuntime, out);
      out.println("  " + role + " " + record.id() + " ready (pid " + record.pid() + ")");
      restarted.add(role);
    }
    out.println(
        "upgrade-cluster complete on machine "
            + machine
            + ": restarted "
            + restarted
            + " (all healthy)");
    return 0;
  }

  /**
   * With no {@code --role} given, every restartable role this machine hosts, in the same boot-order
   * {@code machinePlan} already carries -- AGENT is silently excluded here even if this machine
   * hosts one, since it is never a valid target for this verb. With {@code --role} given, every
   * requested role is validated as one this machine actually hosts, then restarted in that same
   * boot-order sequence regardless of the order the flags were passed in -- restart order safety
   * matters here just as much as boot order safety does for {@code up}.
   */
  private static List<ProcessRole> resolveRoles(
      final MachinePlan machinePlan, final String machine, final List<ProcessRole> requested) {
    if (requested.isEmpty()) {
      return machinePlan.commands().stream()
          .map(ProcessCommand::role)
          .filter(RESTARTABLE_ROLES::contains)
          .distinct()
          .toList();
    }
    final Set<ProcessRole> hosted =
        machinePlan.commands().stream()
            .map(ProcessCommand::role)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    for (final ProcessRole role : requested) {
      if (!hosted.contains(role)) {
        throw new HilmirException(
            "machine '" + machine + "' does not host role " + role + " in this topology");
      }
    }
    final Set<ProcessRole> requestedSet = EnumSet.copyOf(requested);
    return machinePlan.commands().stream()
        .map(ProcessCommand::role)
        .filter(requestedSet::contains)
        .distinct()
        .toList();
  }

  private static List<ProcessRole> parseRoles(final List<String> tokens) {
    final List<ProcessRole> roles = new ArrayList<>();
    for (final String token : tokens) {
      roles.add(parseRole(token));
    }
    return roles;
  }

  private static ProcessRole parseRole(final String token) {
    final ProcessRole role;
    try {
      role = ProcessRole.valueOf(token);
    } catch (final IllegalArgumentException e) {
      throw new HilmirException(
          "invalid --role '"
              + token
              + "' (expected one of STORE, CONTROL_PLANE, FAFNIR, MUNINN, ANDVARI)");
    }
    if (!RESTARTABLE_ROLES.contains(role)) {
      throw new HilmirException(
          "--role "
              + role
              + " is not supported by upgrade-cluster: an agent's own jar only matters at its"
              + " next natural restart, and restarting one risks interrupting in-flight worker"
              + " instances -- this verb only restarts stateless platform processes (STORE,"
              + " CONTROL_PLANE, FAFNIR, MUNINN, ANDVARI)");
    }
    return role;
  }

  private static Path requireFileFlag(final List<String> args) {
    return Path.of(requireFlag(args, "-f"));
  }

  private static String requireFlag(final List<String> args, final String flag) {
    return optionalFlag(args, flag)
        .orElseThrow(() -> new HilmirException(USAGE + "\n\nmissing required flag: " + flag));
  }

  private static Optional<String> optionalFlag(final List<String> args, final String flag) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals(flag) && i + 1 < args.size()) {
        return Optional.of(args.get(i + 1));
      }
    }
    return Optional.empty();
  }

  private static List<String> allValues(final List<String> args, final String flag) {
    final List<String> values = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals(flag) && i + 1 < args.size()) {
        values.add(args.get(i + 1));
      }
    }
    return values;
  }
}
