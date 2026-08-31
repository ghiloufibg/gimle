package com.gimle.cli;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.net.DnsCacheTtl;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The CLI's entry point and global-flag/verb dispatch: a {@code kubectl}-shaped client for familiar
 * muscle memory, with no claim of Kubernetes API compatibility:
 *
 * <pre>
 *   gimle get deployments [name]
 *   gimle get jobs [name]
 *   gimle get cronjobs [name]
 *   gimle get daemonsets [name]
 *   gimle get statefulsets [name]
 *   gimle apply -f &lt;manifest.yaml&gt;   (kind: Deployment, Job, CronJob, DaemonSet, StatefulSet,
 *                                     ArtifactSet, KindDefinition, or any defined custom kind,
 *                                     read from the file itself)
 *   gimle kinds
 *   gimle get &lt;custom-kind|plural|shortName&gt; [name] [--tenant &lt;id&gt;]
 *   gimle delete &lt;custom-kind|plural|shortName&gt; &lt;name&gt; [--tenant &lt;id&gt;]
 *   gimle delete kinddefinition &lt;kind&gt;
 *   gimle delete deployment &lt;name&gt;
 *   gimle delete job &lt;name&gt;
 *   gimle delete cronjob &lt;name&gt;
 *   gimle delete daemonset &lt;name&gt;
 *   gimle delete statefulset &lt;name&gt;
 *   gimle deployment revisions &lt;name&gt;
 *   gimle deployment rollback &lt;name&gt; [--to-revision N]
 *   gimle statefulset revisions &lt;name&gt;
 *   gimle statefulset rollback &lt;name&gt; [--to-revision N]
 *   gimle daemonset revisions &lt;name&gt;
 *   gimle daemonset rollback &lt;name&gt; [--to-revision N]
 *   gimle cronjob trigger &lt;name&gt;
 *   gimle config versions &lt;tenantId&gt; &lt;key&gt;
 *   gimle config rollback &lt;tenantId&gt; &lt;key&gt; &lt;version&gt;
 *   gimle get nodes
 *   gimle get node-assignments &lt;nodeId&gt;
 *   gimle cordon &lt;nodeId&gt;
 *   gimle uncordon &lt;nodeId&gt;
 *   gimle taint &lt;nodeId&gt; &lt;tenantId&gt;
 *   gimle untaint &lt;nodeId&gt; &lt;tenantId&gt;
 *   gimle events &lt;deploymentName&gt; &lt;instanceIndex&gt; [--tenant &lt;id&gt;] [--limit N]
 *   gimle get services [name]
 *   gimle set service &lt;name&gt; (--deployment &lt;name&gt; [--deployment ...] | --external-name &lt;host&gt;) --port N [--target-port N] [--session-affinity]
 *                             [--tenant &lt;id&gt;]
 *   gimle delete service &lt;name&gt;
 *   gimle service endpoints &lt;name&gt;
 *   gimle get networkpolicies [name]
 *   gimle set networkpolicy &lt;name&gt; --tenant &lt;id&gt; [--deployment ...] [--service-interface ...]
 *                                   [--allowed-caller-tenant &lt;id&gt; ... | --deny-all-callers]
 *                                   [--allowed-callee-tenant &lt;id&gt; ... | --deny-all-callees]
 *   gimle delete networkpolicy &lt;name&gt;
 *   gimle get tenants [id]
 *   gimle set tenant &lt;id&gt; --max-memory-bytes N --max-cpu-millicores N --max-instances N
 *   gimle delete tenant &lt;id&gt;
 *   gimle get limitranges [tenantId]
 *   gimle set limitrange &lt;tenantId&gt; [--min-request-memory M --min-request-cpu M]
 *                                    [--max-request-memory M --max-request-cpu M]
 *                                    [--min-limit-memory M --min-limit-cpu M]
 *                                    [--max-limit-memory M --max-limit-cpu M]
 *   gimle delete limitrange &lt;tenantId&gt;
 *   gimle get config &lt;tenantId&gt;
 *   gimle set config &lt;tenantId&gt; &lt;key&gt; &lt;value&gt; [--encrypted]
 *   gimle delete config &lt;tenantId&gt; &lt;key&gt;
 *   gimle secret list &lt;tenantId&gt;
 *   gimle secret get &lt;tenantId&gt; &lt;key&gt; [--version N]
 *   gimle secret set &lt;tenantId&gt; &lt;key&gt; --value &lt;v&gt;
 *   gimle secret delete &lt;tenantId&gt; &lt;key&gt; [--destroy]
 *   gimle secret versions &lt;tenantId&gt; &lt;key&gt;
 *   gimle secret rotate-key
 *   gimle secret retire-key &lt;keyId&gt;
 *   gimle configmap list &lt;tenantId&gt;
 *   gimle configmap get &lt;tenantId&gt; &lt;name&gt;
 *   gimle configmap set &lt;tenantId&gt; &lt;name&gt; [--from-literal key=value ...] [--from-file
 *                                          path|key=path ...]
 *   gimle configmap delete &lt;tenantId&gt; &lt;name&gt;
 *   gimle secretmap list &lt;tenantId&gt;
 *   gimle secretmap get &lt;tenantId&gt; &lt;name&gt;
 *   gimle secretmap set &lt;tenantId&gt; &lt;name&gt; [--from-literal key=value ...] [--from-file
 *                                          path|key=path ...]
 *   gimle secretmap replace &lt;tenantId&gt; &lt;name&gt; [--from-literal key=value ...] [--from-file
 *                                              path|key=path ...]
 *   gimle secretmap delete &lt;tenantId&gt; &lt;name&gt; [--destroy]
 *   gimle secretmap versions &lt;tenantId&gt; &lt;name&gt;
 *   gimle secretmap rollback &lt;tenantId&gt; &lt;name&gt; &lt;groupVersion&gt;
 *   gimle secretmap seal &lt;tenantId&gt; &lt;name&gt; --from-sealed key=path [...]
 *   gimle seal public-key [--out &lt;path&gt;]
 *   gimle seal value &lt;plaintext&gt; --public-key &lt;path&gt; --tenant &lt;id&gt; --name &lt;name&gt; --key &lt;key&gt;
 *                     [--out &lt;path&gt;]
 *   gimle seal rotate-key
 *   gimle seal retire-key &lt;keyId&gt;
 *   gimle artifact push &lt;jar&gt; [--tenant &lt;id&gt;] [--vessel --name &lt;moduleId&gt; --version &lt;version&gt;]
 *   gimle artifact list [moduleId]
 *   gimle artifact get &lt;moduleId&gt; &lt;version&gt; [--to &lt;path&gt;]
 *   gimle artifact delete &lt;moduleId&gt; &lt;version&gt;
 *   gimle backup create [--to &lt;path&gt;]
 *   gimle backup restore &lt;path&gt;
 *   gimle logs &lt;target&gt; [--category=CAT] [--follow|-f] [--since=&lt;cursor&gt;]
 *   gimle get roles [name]
 *   gimle set role &lt;name&gt; --permission &lt;resource&gt;:&lt;verb&gt;[:&lt;tenant&gt;[:&lt;qualifier&gt;]] [--permission ...]
 *   gimle delete role &lt;name&gt;
 *   gimle get rolebindings [id]
 *   gimle set rolebinding &lt;id&gt; --subject user:&lt;name&gt;|group:&lt;name&gt; --role &lt;name&gt;
 *   gimle delete rolebinding &lt;id&gt;
 *   gimle get accounts [username]
 *   gimle set account &lt;username&gt; --password &lt;value&gt;
 *   gimle delete account &lt;username&gt;
 *   gimle can-i &lt;verb&gt; &lt;resource&gt; [--tenant &lt;id&gt;] [--target &lt;id&gt;]
 * </pre>
 *
 * Global flags (any order, anywhere): {@code --server host:port} (or the {@code GIMLE_SERVER} env
 * var), {@code -o|--output table|json} (default {@code table}, kubectl's own flag).
 */
public final class GimleCli {

  private GimleCli() {}

  public static void main(String[] args) {
    DnsCacheTtl.apply();
    // Since JEP 400 (Java 18), System.out/System.err default to the host's native encoding, not
    // file.encoding -- on a POSIX/C-locale host that turns every non-ASCII byte into '?'. Force
    // UTF-8 explicitly rather than relying on the platform default.
    PrintStream out =
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    PrintStream err =
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    int exitCode = run(args, out, err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /** Testable entry point: returns an exit code instead of calling {@code System.exit}. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      dispatch(args, out, err);
      return 0;
    } catch (CliException e) {
      err.println("error: " + e.getMessage());
      return 1;
    } catch (GimleManifestException e) {
      // Client-side manifest parsing/validation (apply -f reads the file before any HTTP call)
      // throws the platform's own manifest exception, not CliException -- a user's own YAML
      // mistake, reported as such rather than through the unexpected-failure catch-all below.
      err.println("error: invalid manifest: " + e.getMessage());
      return 1;
    } catch (RuntimeException e) {
      err.println("error: unexpected failure: " + e.getMessage());
      return 1;
    }
  }

  private static void dispatch(String[] args, PrintStream out, PrintStream err) {
    Deque<String> tokens = new ArrayDeque<>(List.of(args));
    String server = System.getenv("GIMLE_SERVER");
    OutputFormat.Kind output = OutputFormat.Kind.TABLE;
    List<String> positional = new ArrayList<>();

    while (!tokens.isEmpty()) {
      String token = tokens.poll();
      switch (token) {
        case "--server" -> server = requireValue(tokens, "--server");
        case "-o", "--output" -> output = parseOutputKind(requireValue(tokens, "-o"));
        default -> positional.add(token);
      }
    }

    if (positional.isEmpty()) {
      throw new CliException(usage());
    }

    String verb = positional.get(0);
    List<String> rest = positional.subList(1, positional.size());

    // -h/--help scopes to wherever it appears: bare as the verb itself means "show me everything"
    // (gimle -h), while anywhere in a verb's own arguments means "show me just this verb/resource's
    // own usage" (gimle get deployments -h) -- neither ever requires a configured --server, the
    // same "help never needs a server" property this already had before it was made to scope.
    if (isHelpFlag(verb)) {
      out.println(usage());
      return;
    }
    if (containsHelpFlag(rest)) {
      out.println(scopedUsage(verb, rest));
      return;
    }

    if (server == null || server.isBlank()) {
      throw new CliException(
          "no control-plane server configured (pass --server host:port or set GIMLE_SERVER)");
    }

    // Handled before the shared client below: `cert request`/`cert status` deliberately run
    // before any client certificate exists yet, so CertCommand builds exactly the client each of
    // its own subcommands needs (trust-only for those two, fully-authenticated for the rest)
    // rather than sharing one constructed up front -- the default ControlPlaneClient constructor
    // requires gimle.tls.certFile/keyFile to already exist, which is precisely what those two
    // subcommands run before.
    if (verb.equals("cert")) {
      new CertCommand(server, output, out).run(rest);
      return;
    }

    CertCommand.warnIfRenewalDue(out);
    ControlPlaneClient client = new ControlPlaneClient(server);
    switch (verb) {
      case "apply" -> handleApply(rest, client, output, out, err);
      case "kinds" -> new CustomResourceCommand(client, output, out).kinds();
      case "get" -> handleGet(rest, client, output, out);
      case "set" -> handleSet(rest, client, output, out);
      case "delete" -> handleDelete(rest, client, output, out);
      case "logs" -> new LogsCommand(client, out).run(rest);
      case "cordon" -> new NodesCommand(client, output, out).cordon(requireOne(rest, "cordon"));
      case "uncordon" ->
          new NodesCommand(client, output, out).uncordon(requireOne(rest, "uncordon"));
      case "taint" -> handleTaint(rest, client, output, out, true);
      case "untaint" -> handleTaint(rest, client, output, out, false);
      case "events" -> handleEvents(rest, client, output, out);
      case "secret", "secrets" -> new SecretCommand(client, output, out).run(rest);
      case "configmap", "configmaps" -> new ConfigMapCommand(client, output, out).run(rest);
      case "secretmap", "secretmaps" -> new SecretMapCommand(client, output, out).run(rest);
      case "seal", "seals" -> new SealCommand(client, output, out).run(rest);
      case "artifact", "artifacts" -> new ArtifactCommand(client, output, out).run(rest);
      case "backup" -> new BackupCommand(client, output, out).run(rest);
      case "volume", "volumes" -> new VolumesCommand(client, output, out).run(rest);
      case "cronjob", "cronjobs" -> handleCronJobVerb(rest, client, output, out);
      case "config", "configs" -> handleConfigVerb(rest, client, output, out);
      case "audit" -> new AuditCommand(client, output, out).run(rest);
      case "can-i" -> new CanICommand(client, output, out).run(rest);
      case "service", "services" -> handleServiceVerb(rest, client, output, out);
      case "deployment", "deployments" -> handleDeploymentVerb(rest, client, output, out);
      case "statefulset", "statefulsets" -> handleStatefulSetVerb(rest, client, output, out);
      case "daemonset", "daemonsets" -> handleDaemonSetVerb(rest, client, output, out);
      default -> throw new CliException(usage());
    }
  }

  /**
   * {@code apply -f} is kind-dispatched, not noun-dispatched (unlike {@code get}/{@code set}/
   * {@code delete} above) -- the same {@code kubectl apply -f x.yaml} convention this CLI already
   * follows elsewhere: the manifest file's own {@code kind:} field says what it is, so there's no
   * separate {@code job apply -f}/{@code deployment apply -f} verb pair to remember. This peeks at
   * {@code kind:} only to route to the right command; {@link DeploymentsCommand#apply}/{@link
   * JobsCommand#apply} each independently re-read the file for their own {@code name:} extraction
   * and PUT, the same small-duplication shape those two classes already share for everything else.
   */
  private static void handleApply(
      List<String> args,
      ControlPlaneClient client,
      OutputFormat.Kind output,
      PrintStream out,
      PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    switch (ManifestFiles.extractKind(file)) {
      case "Deployment" -> new DeploymentsCommand(client, output, out).apply(args, err);
      case "Job" -> new JobsCommand(client, output, out).apply(args, err);
      case "CronJob" -> new CronJobsCommand(client, output, out).apply(args, err);
      case "DaemonSet" -> new DaemonSetsCommand(client, output, out).apply(args, err);
      case "StatefulSet" -> new StatefulSetsCommand(client, output, out).apply(args, err);
      case "ArtifactSet" -> new ArtifactSetCommand(client, output, out).apply(args);
      case "KindDefinition" ->
          new CustomResourceCommand(client, output, out).applyKindDefinition(args, err);
      // No client-side "unknown manifest kind" hard error any more: an unrecognized kind routes
      // to the generic custom-resource PUT, and whether it names a defined kind is decided
      // server-side, where the definition catalog lives -- an undefined one comes back as a 400
      // carrying that catalog.
      case String other -> new CustomResourceCommand(client, output, out).apply(other, args, err);
    }
  }

  /**
   * {@code cronjob}/{@code cronjobs} as a distinct top-level verb -- not just noun dispatch under
   * {@code get}/{@code delete} -- for the same reason {@code secret} is: it needs an action ({@code
   * trigger}) that three-verb dispatch has no shape for.
   */
  private static void handleCronJobVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle cronjob trigger <name>");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (action) {
      case "trigger" -> new CronJobsCommand(client, output, out).trigger(rest);
      default -> throw new CliException("unknown cronjob action: " + action);
    }
  }

  /**
   * {@code config}'s own get/set/delete stay folded into {@link #handleGet}/{@link #handleSet}/
   * {@link #handleDelete} above -- unchanged from before this fix -- but {@code versions}/{@code
   * rollback} have no shape in that three-verb dispatch (the same reason {@code secret} split off
   * into its own top-level verb once it needed more than three actions, see {@link SecretCommand}'s
   * own javadoc), so they're reached through this new, narrower top-level verb instead of folding
   * {@code config} entirely into {@link ConfigMapCommand}'s style.
   */
  private static void handleConfigVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle config versions|rollback ...");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    ConfigCommand command = new ConfigCommand(client, output, out);
    switch (action) {
      case "versions" -> command.versions(rest);
      case "rollback" -> command.rollback(rest);
      default -> throw new CliException("unknown config action: " + action);
    }
  }

  private static void handleEvents(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.size() < 2) {
      throw new CliException(
          "usage: gimle events <deploymentName> <instanceIndex> [--tenant <id>] [--limit N]");
    }
    new EventsCommand(client, output, out)
        .run(args.get(0), args.get(1), args.subList(2, args.size()));
  }

  private static void handleTaint(
      List<String> args,
      ControlPlaneClient client,
      OutputFormat.Kind output,
      PrintStream out,
      boolean tainted) {
    if (args.size() < 2) {
      throw new CliException(
          "usage: gimle " + (tainted ? "taint" : "untaint") + " <nodeId> <tenantId>");
    }
    NodesCommand command = new NodesCommand(client, output, out);
    if (tainted) {
      command.taint(args.get(0), args.get(1));
    } else {
      command.untaint(args.get(0), args.get(1));
    }
  }

  /**
   * {@code service}/{@code services} as a distinct top-level verb -- not just noun dispatch under
   * {@code get}/{@code set}/{@code delete} below -- for the same reason {@code cronjob} is: {@code
   * endpoints} is an action three-verb dispatch has no shape for.
   */
  private static void handleServiceVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle service endpoints <name>");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (action) {
      case "endpoints" -> new ServicesCommand(client, output, out).endpoints(rest);
      default -> throw new CliException("unknown service action: " + action);
    }
  }

  /**
   * {@code deployment}/{@code deployments} as a distinct top-level verb -- not just noun dispatch
   * under {@code get}/{@code delete} above -- for the same reason {@code cronjob}/{@code service}
   * are: {@code rollback}/{@code revisions} are actions three-verb dispatch has no shape for.
   * Additive alongside the existing {@code get deployment}/{@code delete deployment} noun dispatch,
   * which keeps working unchanged.
   */
  private static void handleDeploymentVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(
          "usage: gimle deployment rollback <name> [--to-revision N] | gimle deployment revisions"
              + " <name>");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (action) {
      case "rollback" -> new DeploymentsCommand(client, output, out).rollback(rest);
      case "revisions" -> new DeploymentsCommand(client, output, out).revisions(rest);
      default -> throw new CliException("unknown deployment action: " + action);
    }
  }

  /** Mirrors {@link #handleDeploymentVerb} exactly, for StatefulSet. */
  private static void handleStatefulSetVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(
          "usage: gimle statefulset rollback <name> [--to-revision N] | gimle statefulset"
              + " revisions <name>");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (action) {
      case "rollback" -> new StatefulSetsCommand(client, output, out).rollback(rest);
      case "revisions" -> new StatefulSetsCommand(client, output, out).revisions(rest);
      default -> throw new CliException("unknown statefulset action: " + action);
    }
  }

  /** Mirrors {@link #handleDeploymentVerb} exactly, for DaemonSet. */
  private static void handleDaemonSetVerb(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(
          "usage: gimle daemonset rollback <name> [--to-revision N] | gimle daemonset revisions"
              + " <name>");
    }
    String action = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (action) {
      case "rollback" -> new DaemonSetsCommand(client, output, out).rollback(rest);
      case "revisions" -> new DaemonSetsCommand(client, output, out).revisions(rest);
      default -> throw new CliException("unknown daemonset action: " + action);
    }
  }

  private static void handleGet(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String noun = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (noun) {
      case "deployment", "deployments" -> new DeploymentsCommand(client, output, out).get(rest);
      case "job", "jobs" -> new JobsCommand(client, output, out).get(rest);
      case "cronjob", "cronjobs" -> new CronJobsCommand(client, output, out).get(rest);
      case "daemonset", "daemonsets" -> new DaemonSetsCommand(client, output, out).get(rest);
      case "statefulset", "statefulsets" -> new StatefulSetsCommand(client, output, out).get(rest);
      case "node", "nodes" -> new NodesCommand(client, output, out).list();
      case "node-assignments" ->
          new NodesCommand(client, output, out).assignments(requireOne(rest, "node-assignments"));
      case "service", "services" -> new ServicesCommand(client, output, out).get(rest);
      case "networkpolicy", "networkpolicies" ->
          new NetworkPolicyCommand(client, output, out).get(rest);
      case "tenant", "tenants" -> new TenantsCommand(client, output, out).get(rest);
      case "limitrange", "limitranges" -> new LimitRangeCommand(client, output, out).get(rest);
      case "config" -> new ConfigCommand(client, output, out).list(requireOne(rest, "config"));
      case "role", "roles" -> new RolesCommand(client, output, out).get(rest);
      case "rolebinding", "rolebindings" -> new RoleBindingsCommand(client, output, out).get(rest);
      case "account", "accounts" -> new AccountsCommand(client, output, out).get(rest);
      // Custom kinds fall through only after every built-in noun above failed to match, resolved
      // against the live definition catalog: exact prefixed kind name, then a definition's
      // declared plural, then its shortNames.
      default -> {
        CustomResourceCommand command = new CustomResourceCommand(client, output, out);
        command
            .resolveKind(noun)
            .ifPresentOrElse(
                kindName -> command.get(kindName, rest),
                () -> {
                  throw unknownResource(noun);
                });
      }
    }
  }

  private static CliException unknownResource(String noun) {
    return new CliException(
        "unknown resource: "
            + noun
            + " -- not a built-in resource, and no KindDefinition matches it by kind name,"
            + " plural, or short name (see 'gimle kinds')");
  }

  private static void handleSet(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String noun = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (noun) {
      case "service" -> new ServicesCommand(client, output, out).set(rest);
      case "networkpolicy" -> new NetworkPolicyCommand(client, output, out).set(rest);
      case "tenant" -> new TenantsCommand(client, output, out).set(rest);
      case "limitrange" -> new LimitRangeCommand(client, output, out).set(rest);
      case "config" -> new ConfigCommand(client, output, out).set(rest);
      case "role" -> new RolesCommand(client, output, out).set(rest);
      case "rolebinding" -> new RoleBindingsCommand(client, output, out).set(rest);
      case "account" -> new AccountsCommand(client, output, out).set(rest);
      default -> throw new CliException("unknown resource for 'set': " + noun);
    }
  }

  private static void handleDelete(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String noun = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (noun) {
      case "deployment", "deployments" -> new DeploymentsCommand(client, output, out).delete(rest);
      case "job", "jobs" -> new JobsCommand(client, output, out).delete(rest);
      case "cronjob", "cronjobs" -> new CronJobsCommand(client, output, out).delete(rest);
      case "daemonset", "daemonsets" -> new DaemonSetsCommand(client, output, out).delete(rest);
      case "statefulset", "statefulsets" ->
          new StatefulSetsCommand(client, output, out).delete(rest);
      case "service", "services" -> new ServicesCommand(client, output, out).delete(rest);
      case "networkpolicy", "networkpolicies" ->
          new NetworkPolicyCommand(client, output, out).delete(rest);
      case "tenant", "tenants" ->
          new TenantsCommand(client, output, out).delete(requireOne(rest, "tenant"));
      case "limitrange", "limitranges" ->
          new LimitRangeCommand(client, output, out).delete(requireOne(rest, "limitrange"));
      case "config" -> new ConfigCommand(client, output, out).delete(rest);
      case "role", "roles" ->
          new RolesCommand(client, output, out).delete(requireOne(rest, "role"));
      case "rolebinding", "rolebindings" ->
          new RoleBindingsCommand(client, output, out).delete(requireOne(rest, "rolebinding"));
      case "account", "accounts" ->
          new AccountsCommand(client, output, out).delete(requireOne(rest, "account"));
      case "kinddefinition", "kinddefinitions" ->
          new CustomResourceCommand(client, output, out)
              .deleteKindDefinition(requireOne(rest, "kinddefinition"));
      // The same custom-kind fallthrough handleGet takes, over the identical noun resolution.
      default -> {
        CustomResourceCommand command = new CustomResourceCommand(client, output, out);
        command
            .resolveKind(noun)
            .ifPresentOrElse(
                kindName -> command.delete(kindName, rest),
                () -> {
                  throw unknownResource(noun);
                });
      }
    }
  }

  private static boolean isHelpFlag(String token) {
    return "-h".equals(token) || "--help".equals(token);
  }

  private static boolean containsHelpFlag(List<String> args) {
    return args.stream().anyMatch(GimleCli::isHelpFlag);
  }

  /**
   * The first argument that isn't itself a help flag, e.g. the resource noun in {@code get
   * deployments -h}, or {@code null} when only help flags remain (e.g. bare {@code get -h}).
   */
  private static String firstNonHelpToken(List<String> args) {
    for (String arg : args) {
      if (!isHelpFlag(arg)) {
        return arg;
      }
    }
    return null;
  }

  /**
   * Resolves {@code -h}/{@code --help} to the narrowest usage text available for where it appeared:
   * a bare verb ({@code gimle get -h}) gets that verb's own resource listing; a verb plus a
   * resource noun ({@code gimle get deployments -h}) gets just that one form. Falls back to the
   * full top-level usage for a verb this table doesn't recognize, which only ever happens when a
   * genuinely unknown verb was typed alongside {@code -h} -- the same case that verb's own dispatch
   * would reject anyway.
   */
  private static String scopedUsage(String verb, List<String> rest) {
    String noun = firstNonHelpToken(rest);
    return switch (verb) {
      case "get" -> noun == null ? GET_USAGE : GET_NOUN_USAGE.getOrDefault(noun, GET_USAGE);
      case "set" -> noun == null ? SET_USAGE : SET_NOUN_USAGE.getOrDefault(noun, SET_USAGE);
      case "delete" ->
          noun == null ? DELETE_USAGE : DELETE_NOUN_USAGE.getOrDefault(noun, DELETE_USAGE);
      case "apply" -> APPLY_USAGE;
      case "kinds" -> KINDS_USAGE;
      case "secret", "secrets" -> SecretCommand.usage();
      case "configmap", "configmaps" -> ConfigMapCommand.usage();
      case "secretmap", "secretmaps" -> SecretMapCommand.usage();
      case "seal", "seals" -> SealCommand.usage();
      case "artifact", "artifacts" -> ArtifactCommand.usage();
      case "backup" -> BackupCommand.usage();
      case "volume", "volumes" -> VolumesCommand.usage();
      case "cronjob", "cronjobs" -> CRONJOB_USAGE;
      case "config", "configs" -> CONFIG_VERB_USAGE;
      case "audit" -> AuditCommand.usage();
      case "can-i" -> CAN_I_USAGE;
      case "service", "services" -> SERVICE_VERB_USAGE;
      case "deployment", "deployments" -> DEPLOYMENT_VERB_USAGE;
      case "statefulset", "statefulsets" -> STATEFULSET_VERB_USAGE;
      case "daemonset", "daemonsets" -> DAEMONSET_VERB_USAGE;
      case "logs" -> LogsCommand.usage();
      case "events" -> EVENTS_USAGE;
      case "cordon" -> "usage: gimle cordon <nodeId>";
      case "uncordon" -> "usage: gimle uncordon <nodeId>";
      case "taint" -> "usage: gimle taint <nodeId> <tenantId>";
      case "untaint" -> "usage: gimle untaint <nodeId> <tenantId>";
      case "cert" -> CertCommand.usage();
      default -> usage();
    };
  }

  private static final String GET_USAGE =
      """
      usage: gimle get <resource> [args]

      resources:
        deployments [name]
        jobs [name]
        cronjobs [name]
        daemonsets [name]
        statefulsets [name]
        nodes
        node-assignments <nodeId>
        services [name]
        networkpolicies [name]
        tenants [id]
        limitranges [tenantId]
        config <tenantId>
        roles [name]
        rolebindings [id]
        accounts [username]
        <custom-kind|plural|shortName> [name] [--tenant <id>]   (any kind defined via 'gimle kinds')""";

  private static final Map<String, String> GET_NOUN_USAGE =
      Map.ofEntries(
          Map.entry("deployment", "usage: gimle get deployments [name]"),
          Map.entry("deployments", "usage: gimle get deployments [name]"),
          Map.entry("job", "usage: gimle get jobs [name]"),
          Map.entry("jobs", "usage: gimle get jobs [name]"),
          Map.entry("cronjob", "usage: gimle get cronjobs [name]"),
          Map.entry("cronjobs", "usage: gimle get cronjobs [name]"),
          Map.entry("daemonset", "usage: gimle get daemonsets [name]"),
          Map.entry("daemonsets", "usage: gimle get daemonsets [name]"),
          Map.entry("statefulset", "usage: gimle get statefulsets [name]"),
          Map.entry("statefulsets", "usage: gimle get statefulsets [name]"),
          Map.entry("node", "usage: gimle get nodes"),
          Map.entry("nodes", "usage: gimle get nodes"),
          Map.entry("node-assignments", "usage: gimle get node-assignments <nodeId>"),
          Map.entry("service", "usage: gimle get services [name]"),
          Map.entry("services", "usage: gimle get services [name]"),
          Map.entry("networkpolicy", "usage: gimle get networkpolicies [name]"),
          Map.entry("networkpolicies", "usage: gimle get networkpolicies [name]"),
          Map.entry("tenant", "usage: gimle get tenants [id]"),
          Map.entry("tenants", "usage: gimle get tenants [id]"),
          Map.entry("limitrange", "usage: gimle get limitranges [tenantId]"),
          Map.entry("limitranges", "usage: gimle get limitranges [tenantId]"),
          Map.entry("config", "usage: gimle get config <tenantId>"),
          Map.entry("role", "usage: gimle get roles [name]"),
          Map.entry("roles", "usage: gimle get roles [name]"),
          Map.entry("rolebinding", "usage: gimle get rolebindings [id]"),
          Map.entry("rolebindings", "usage: gimle get rolebindings [id]"),
          Map.entry("account", "usage: gimle get accounts [username]"),
          Map.entry("accounts", "usage: gimle get accounts [username]"));

  private static final String SET_USAGE =
      """
      usage: gimle set <resource> [args]

      resources:
        service
        networkpolicy
        tenant
        limitrange
        config
        role
        rolebinding
        account""";

  private static final Map<String, String> SET_NOUN_USAGE =
      Map.ofEntries(
          Map.entry(
              "service",
              """
              usage: gimle set service <name> (--deployment <name> [--deployment ...] | --external-name <host>) --port N [--target-port N] [--session-affinity]
                                  [--tenant <id>]"""),
          Map.entry(
              "networkpolicy",
              """
              usage: gimle set networkpolicy <name> --tenant <id> [--deployment ...] [--service-interface ...]
                                        [--allowed-caller-tenant <id> ... | --deny-all-callers]
                                        [--allowed-callee-tenant <id> ... | --deny-all-callees]"""),
          Map.entry(
              "tenant",
              "usage: gimle set tenant <id> --max-memory-bytes N --max-cpu-millicores N"
                  + " --max-instances N"),
          Map.entry(
              "limitrange",
              """
              usage: gimle set limitrange <tenantId> [--min-request-memory M --min-request-cpu M]
                                         [--max-request-memory M --max-request-cpu M]
                                         [--min-limit-memory M --min-limit-cpu M]
                                         [--max-limit-memory M --max-limit-cpu M]"""),
          Map.entry("config", "usage: gimle set config <tenantId> <key> <value> [--encrypted]"),
          Map.entry(
              "role",
              "usage: gimle set role <name> --permission <resource>:<verb>[:<tenant>[:<qualifier>]]"
                  + " [--permission ...]"),
          Map.entry(
              "rolebinding",
              "usage: gimle set rolebinding <id> --subject user:<name>|group:<name> --role"
                  + " <name>"),
          Map.entry("account", "usage: gimle set account <username> --password <value>"));

  private static final String DELETE_USAGE =
      """
      usage: gimle delete <resource> <name/id>

      resources:
        deployment
        job
        cronjob
        daemonset
        statefulset
        service
        networkpolicy
        tenant
        limitrange
        config
        role
        rolebinding
        account
        kinddefinition <kind>
        <custom-kind|plural|shortName> <name> [--tenant <id>]""";

  private static final Map<String, String> DELETE_NOUN_USAGE =
      Map.ofEntries(
          Map.entry("deployment", "usage: gimle delete deployment <name>"),
          Map.entry("deployments", "usage: gimle delete deployment <name>"),
          Map.entry("job", "usage: gimle delete job <name>"),
          Map.entry("jobs", "usage: gimle delete job <name>"),
          Map.entry("cronjob", "usage: gimle delete cronjob <name>"),
          Map.entry("cronjobs", "usage: gimle delete cronjob <name>"),
          Map.entry("daemonset", "usage: gimle delete daemonset <name>"),
          Map.entry("daemonsets", "usage: gimle delete daemonset <name>"),
          Map.entry("statefulset", "usage: gimle delete statefulset <name>"),
          Map.entry("statefulsets", "usage: gimle delete statefulset <name>"),
          Map.entry("service", "usage: gimle delete service <name>"),
          Map.entry("services", "usage: gimle delete service <name>"),
          Map.entry("networkpolicy", "usage: gimle delete networkpolicy <name>"),
          Map.entry("networkpolicies", "usage: gimle delete networkpolicy <name>"),
          Map.entry("tenant", "usage: gimle delete tenant <id>"),
          Map.entry("tenants", "usage: gimle delete tenant <id>"),
          Map.entry("limitrange", "usage: gimle delete limitrange <tenantId>"),
          Map.entry("limitranges", "usage: gimle delete limitrange <tenantId>"),
          Map.entry("config", "usage: gimle delete config <tenantId> <key>"),
          Map.entry("role", "usage: gimle delete role <name>"),
          Map.entry("roles", "usage: gimle delete role <name>"),
          Map.entry("rolebinding", "usage: gimle delete rolebinding <id>"),
          Map.entry("rolebindings", "usage: gimle delete rolebinding <id>"),
          Map.entry("account", "usage: gimle delete account <username>"),
          Map.entry("accounts", "usage: gimle delete account <username>"),
          Map.entry("kinddefinition", "usage: gimle delete kinddefinition <kind>"),
          Map.entry("kinddefinitions", "usage: gimle delete kinddefinition <kind>"));

  private static final String APPLY_USAGE =
      """
      usage: gimle apply -f <file.yaml>

      kind: Deployment, Job, CronJob, DaemonSet, StatefulSet, ArtifactSet, KindDefinition, or any
      defined custom kind (see 'gimle kinds'), read from the manifest file's own 'kind:' field""";

  private static final String KINDS_USAGE =
      "usage: gimle kinds   (lists every KindDefinition: name, scope, declared names, instance"
          + " count)";

  private static final String CRONJOB_USAGE = "usage: gimle cronjob trigger <name>";

  private static final String CONFIG_VERB_USAGE =
      """
      usage: gimle config <action> [args]

      actions:
        versions <tenantId> <key>
        rollback <tenantId> <key> <version>
      """;

  private static final String CAN_I_USAGE =
      "usage: gimle can-i <verb> <resource> [--tenant <id>] [--target <id>]";

  private static final String SERVICE_VERB_USAGE = "usage: gimle service endpoints <name>";

  private static final String DEPLOYMENT_VERB_USAGE =
      "usage: gimle deployment rollback <name> [--to-revision N] | gimle deployment revisions"
          + " <name>";

  private static final String STATEFULSET_VERB_USAGE =
      "usage: gimle statefulset rollback <name> [--to-revision N] | gimle statefulset revisions"
          + " <name>";

  private static final String DAEMONSET_VERB_USAGE =
      "usage: gimle daemonset rollback <name> [--to-revision N] | gimle daemonset revisions"
          + " <name>";

  private static final String EVENTS_USAGE =
      "usage: gimle events <deploymentName> <instanceIndex> [--tenant <id>] [--limit N]";

  private static String requireOne(List<String> args, String what) {
    if (args.isEmpty()) {
      throw new CliException("missing " + what + " name/id");
    }
    return requireAtMostOne(args, what);
  }

  /**
   * Rejects more than one positional argument outright instead of silently keeping only the first
   * and discarding the rest -- {@code null} for an empty {@code args}, which every {@code get
   * <kind> [name]}-style caller already treats as "list every {@code kind}" rather than an error.
   * {@link #requireOne} builds on this for the {@code delete}-style callers that have no such
   * empty-means-list-everything case of their own. Package-private: {@code TenantsCommand}/{@code
   * RolesCommand}/{@code RoleBindingsCommand}/{@code AccountsCommand}/{@code LimitRangeCommand}'s
   * own {@code get} methods call this directly for the identical reason {@code requireOne} exists
   * -- before this, {@code gimle get tenant a b c} silently printed only {@code a}, with {@code b}
   * and {@code c} never referenced anywhere, no different from the {@code delete} side of the same
   * bug.
   */
  static String requireAtMostOne(List<String> args, String what) {
    if (args.isEmpty()) {
      return null;
    }
    if (args.size() > 1) {
      throw new CliException(
          "too many arguments for "
              + what
              + ": expected at most one name/id, got "
              + args.size()
              + " ("
              + String.join(", ", args)
              + ")");
    }
    return args.get(0);
  }

  private static String requireValue(Deque<String> tokens, String flag) {
    if (tokens.isEmpty()) {
      throw new CliException(flag + " requires a value");
    }
    return tokens.poll();
  }

  private static OutputFormat.Kind parseOutputKind(String value) {
    return switch (value) {
      case "table" -> OutputFormat.Kind.TABLE;
      case "json" -> OutputFormat.Kind.JSON;
      default ->
          throw new CliException("unknown output format: " + value + " (expected table or json)");
    };
  }

  private static String usage() {
    return """
        usage: gimle <verb> <resource> [args] [--server host:port] [-o table|json]

        verbs:
          get deployments [name]
          get jobs [name]
          get cronjobs [name]
          get daemonsets [name]
          get statefulsets [name]
          apply -f <file.yaml>   (kind: Deployment, Job, CronJob, DaemonSet, StatefulSet, ArtifactSet, KindDefinition, or any defined custom kind, read from the file itself)
          kinds
          get <custom-kind|plural|shortName> [name] [--tenant <id>]
          delete <custom-kind|plural|shortName> <name> [--tenant <id>]
          delete kinddefinition <kind>
          delete deployment <name>
          delete job <name>
          delete cronjob <name>
          delete daemonset <name>
          delete statefulset <name>
          deployment revisions <name>
          deployment rollback <name> [--to-revision N]
          statefulset revisions <name>
          statefulset rollback <name> [--to-revision N]
          daemonset revisions <name>
          daemonset rollback <name> [--to-revision N]
          cronjob trigger <name>
          config versions <tenantId> <key>
          config rollback <tenantId> <key> <version>
          get nodes
          get node-assignments <nodeId>
          cordon <nodeId>
          uncordon <nodeId>
          taint <nodeId> <tenantId>
          untaint <nodeId> <tenantId>
          volume list
          volume destroy <statefulSet> <instanceIndex> --node <nodeId>
          events <deploymentName> <instanceIndex> [--tenant <id>] [--limit N]
          get services [name]
          set service <name> (--deployment <name> [--deployment ...] | --external-name <host>) --port N [--target-port N] [--session-affinity]
                              [--tenant <id>]
          delete service <name>
          service endpoints <name>
          get networkpolicies [name]
          set networkpolicy <name> --tenant <id> [--deployment ...] [--service-interface ...]
                                    [--allowed-caller-tenant <id> ... | --deny-all-callers]
                                    [--allowed-callee-tenant <id> ... | --deny-all-callees]
          delete networkpolicy <name>
          get tenants [id]
          set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
          delete tenant <id>
          get limitranges [tenantId]
          set limitrange <tenantId> [--min-request-memory M --min-request-cpu M]
                                     [--max-request-memory M --max-request-cpu M]
                                     [--min-limit-memory M --min-limit-cpu M]
                                     [--max-limit-memory M --max-limit-cpu M]
          delete limitrange <tenantId>
          get config <tenantId>
          set config <tenantId> <key> <value> [--encrypted]
          delete config <tenantId> <key>
          secret list <tenantId>
          secret get <tenantId> <key> [--version N]
          secret set <tenantId> <key> --value <v>
          secret delete <tenantId> <key> [--destroy]
          secret versions <tenantId> <key>
          secret rotate-key
          secret retire-key <keyId>
          configmap list <tenantId>
          configmap get <tenantId> <name>
          configmap set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          configmap delete <tenantId> <name>
          secretmap list <tenantId>
          secretmap get <tenantId> <name>
          secretmap set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          secretmap replace <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          secretmap delete <tenantId> <name> [--destroy]
          secretmap versions <tenantId> <name>
          secretmap rollback <tenantId> <name> <groupVersion>
          secretmap seal <tenantId> <name> --from-sealed key=path [...]
          seal public-key [--out <path>]
          seal value <plaintext> --public-key <path> --tenant <id> --name <name> --key <key> [--out <path>]
          seal rotate-key
          seal retire-key <keyId>
          artifact push <jar> [--tenant <id>] [--vessel --name <moduleId> --version <version>]
          artifact list [moduleId]
          artifact get <moduleId> <version> [--to <path>]
          artifact delete <moduleId> <version>
          backup create [--to <path>]
          backup restore <path>
          audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                     [--since <epochMillis>] [--limit N]
          logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
          get roles [name]
          set role <name> --permission <resource>:<verb>[:<tenant>[:<qualifier>]] [--permission ...]
          delete role <name>
          get rolebindings [id]
          set rolebinding <id> --subject user:<name>|group:<name> --role <name>
          delete rolebinding <id>
          get accounts [username]
          set account <username> --password <value>
          delete account <username>
          can-i <verb> <resource> [--tenant <id>] [--target <id>]
          cert token create [--ttl <duration>]
          cert request --purpose operator|node|tenant [--tenant <id>] --out-cert <path> --out-key <path>
          cert status <request-id> --out-cert <path>
          cert approve <request-id>
          cert renew [--force]
          cert revoke <serialHex>
          cert unrevoke <serialHex>
          cert revocations""";
  }
}
