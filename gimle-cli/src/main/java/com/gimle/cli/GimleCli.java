package com.gimle.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The CLI's entry point and global-flag/verb dispatch: a {@code kubectl}-shaped client for familiar
 * muscle memory, with no claim of Kubernetes API compatibility:
 *
 * <pre>
 *   gimle get deployments [name]
 *   gimle get jobs [name]
 *   gimle apply -f &lt;manifest.yaml&gt;   (kind: Deployment or kind: Job, read from the file itself)
 *   gimle delete deployment &lt;name&gt;
 *   gimle delete job &lt;name&gt;
 *   gimle get nodes
 *   gimle get node-assignments &lt;nodeId&gt;
 *   gimle cordon &lt;nodeId&gt;
 *   gimle uncordon &lt;nodeId&gt;
 *   gimle events &lt;deploymentName&gt; &lt;instanceIndex&gt;
 *   gimle get tenants [id]
 *   gimle set tenant &lt;id&gt; --max-memory-bytes N --max-cpu-millicores N --max-instances N
 *   gimle delete tenant &lt;id&gt;
 *   gimle get config &lt;tenantId&gt;
 *   gimle set config &lt;tenantId&gt; &lt;key&gt; &lt;value&gt; [--encrypted]
 *   gimle delete config &lt;tenantId&gt; &lt;key&gt;
 *   gimle secret list &lt;tenantId&gt;
 *   gimle secret get &lt;tenantId&gt; &lt;key&gt; [--version N]
 *   gimle secret set &lt;tenantId&gt; &lt;key&gt; --value &lt;v&gt;
 *   gimle secret delete &lt;tenantId&gt; &lt;key&gt; [--destroy]
 *   gimle secret versions &lt;tenantId&gt; &lt;key&gt;
 *   gimle secret rotate-key
 *   gimle logs &lt;target&gt; [--category=CAT] [--follow|-f] [--since=&lt;cursor&gt;]
 *   gimle get roles [name]
 *   gimle set role &lt;name&gt; --permission &lt;resource&gt;:&lt;verb&gt;[:&lt;tenant&gt;] [--permission ...]
 *   gimle delete role &lt;name&gt;
 *   gimle get rolebindings [id]
 *   gimle set rolebinding &lt;id&gt; --subject user:&lt;name&gt;|group:&lt;name&gt; --role &lt;name&gt;
 *   gimle delete rolebinding &lt;id&gt;
 *   gimle get accounts [username]
 *   gimle set account &lt;username&gt; --password &lt;value&gt;
 *   gimle delete account &lt;username&gt;
 * </pre>
 *
 * Global flags (any order, anywhere): {@code --server host:port} (or the {@code GIMLE_SERVER} env
 * var), {@code -o|--output table|json} (default {@code table}, kubectl's own flag).
 */
public final class GimleCli {

  private GimleCli() {}

  public static void main(String[] args) {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /** Testable entry point: returns an exit code instead of calling {@code System.exit}. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      dispatch(args, out);
      return 0;
    } catch (CliException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
  }

  private static void dispatch(String[] args, PrintStream out) {
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
    if (server == null || server.isBlank()) {
      throw new CliException(
          "no control-plane server configured (pass --server host:port or set GIMLE_SERVER)");
    }

    String verb = positional.get(0);
    List<String> rest = positional.subList(1, positional.size());

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
      case "apply" -> handleApply(rest, client, output, out);
      case "get" -> handleGet(rest, client, output, out);
      case "set" -> handleSet(rest, client, output, out);
      case "delete" -> handleDelete(rest, client, output, out);
      case "logs" -> new LogsCommand(client, out).run(rest);
      case "cordon" -> new NodesCommand(client, output, out).cordon(requireOne(rest, "cordon"));
      case "uncordon" ->
          new NodesCommand(client, output, out).uncordon(requireOne(rest, "uncordon"));
      case "events" -> handleEvents(rest, client, output, out);
      case "secret", "secrets" -> new SecretCommand(client, output, out).run(rest);
      case "audit" -> new AuditCommand(client, output, out).run(rest);
      default -> throw new CliException(usage());
    }
  }

  /**
   * {@code apply -f} is kind-dispatched, not noun-dispatched (unlike {@code get}/{@code set}/
   * {@code delete} above) -- the same {@code kubectl apply -f x.yaml} convention this CLI already
   * follows elsewhere: the manifest file's own {@code kind:} field says what it is, so there's no
   * separate {@code job apply -f}/{@code deployment apply -f} verb pair to remember (priority-3
   * design doc §3e). This peeks at {@code kind:} only to route to the right command; {@link
   * DeploymentsCommand#apply}/{@link JobsCommand#apply} each independently re-read the file for
   * their own {@code name:} extraction and PUT, the same small-duplication shape those two classes
   * already share for everything else.
   */
  private static void handleApply(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    Path file = requireFileFlag(args);
    switch (extractKind(file)) {
      case "Deployment" -> new DeploymentsCommand(client, output, out).apply(args);
      case "Job" -> new JobsCommand(client, output, out).apply(args);
      case String other -> throw new CliException("unknown manifest kind: " + other);
    }
  }

  private static Path requireFileFlag(List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (("-f".equals(args.get(i)) || "--file".equals(args.get(i))) && i + 1 < args.size()) {
        return Path.of(args.get(i + 1));
      }
    }
    throw new CliException("apply requires -f <manifest.yaml>");
  }

  private static String extractKind(Path file) {
    byte[] manifestBytes;
    try {
      manifestBytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new CliException("could not read manifest file " + file + ": " + e.getMessage(), e);
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object parsed;
    try {
      parsed = yaml.load(new ByteArrayInputStream(manifestBytes));
    } catch (RuntimeException e) {
      throw new CliException("malformed manifest " + file + ": " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> map)
        || !(map.get("kind") instanceof String kind)
        || kind.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level 'kind' field");
    }
    return kind;
  }

  private static void handleEvents(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.size() < 2) {
      throw new CliException("usage: gimle events <deploymentName> <instanceIndex>");
    }
    new EventsCommand(client, output, out).run(args.get(0), args.get(1));
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
      case "node", "nodes" -> new NodesCommand(client, output, out).list();
      case "node-assignments" ->
          new NodesCommand(client, output, out).assignments(requireOne(rest, "node-assignments"));
      case "tenant", "tenants" -> new TenantsCommand(client, output, out).get(rest);
      case "config" -> new ConfigCommand(client, output, out).list(requireOne(rest, "config"));
      case "role", "roles" -> new RolesCommand(client, output, out).get(rest);
      case "rolebinding", "rolebindings" -> new RoleBindingsCommand(client, output, out).get(rest);
      case "account", "accounts" -> new AccountsCommand(client, output, out).get(rest);
      default -> throw new CliException("unknown resource: " + noun);
    }
  }

  private static void handleSet(
      List<String> args, ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String noun = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (noun) {
      case "tenant" -> new TenantsCommand(client, output, out).set(rest);
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
      case "deployment", "deployments" ->
          new DeploymentsCommand(client, output, out).delete(requireOne(rest, "deployment"));
      case "job", "jobs" -> new JobsCommand(client, output, out).delete(requireOne(rest, "job"));
      case "tenant", "tenants" ->
          new TenantsCommand(client, output, out).delete(requireOne(rest, "tenant"));
      case "config" -> new ConfigCommand(client, output, out).delete(rest);
      case "role", "roles" ->
          new RolesCommand(client, output, out).delete(requireOne(rest, "role"));
      case "rolebinding", "rolebindings" ->
          new RoleBindingsCommand(client, output, out).delete(requireOne(rest, "rolebinding"));
      case "account", "accounts" ->
          new AccountsCommand(client, output, out).delete(requireOne(rest, "account"));
      default -> throw new CliException("unknown resource for 'delete': " + noun);
    }
  }

  private static String requireOne(List<String> args, String what) {
    if (args.isEmpty()) {
      throw new CliException("missing " + what + " name/id");
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
          apply -f <file.yaml>   (kind: Deployment or kind: Job, read from the file itself)
          delete deployment <name>
          delete job <name>
          get nodes
          get node-assignments <nodeId>
          cordon <nodeId>
          uncordon <nodeId>
          events <deploymentName> <instanceIndex>
          get tenants [id]
          set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
          delete tenant <id>
          get config <tenantId>
          set config <tenantId> <key> <value> [--encrypted]
          delete config <tenantId> <key>
          secret list <tenantId>
          secret get <tenantId> <key> [--version N]
          secret set <tenantId> <key> --value <v>
          secret delete <tenantId> <key> [--destroy]
          secret versions <tenantId> <key>
          secret rotate-key
          audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                     [--since <epochMillis>] [--limit N]
          logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
          get roles [name]
          set role <name> --permission <resource>:<verb>[:<tenant>] [--permission ...]
          delete role <name>
          get rolebindings [id]
          set rolebinding <id> --subject user:<name>|group:<name> --role <name>
          delete rolebinding <id>
          get accounts [username]
          set account <username> --password <value>
          delete account <username>
          cert token create [--ttl <duration>]
          cert request --purpose operator|node --out-cert <path> --out-key <path>
          cert status <request-id> --out-cert <path>
          cert approve <request-id>
          cert renew [--force]""";
  }
}
