package com.gimle.cli;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The CLI's entry point and global-flag/verb dispatch (design: a {@code kubectl}-shaped client for
 * "muscle memory," with no Kubernetes API compatibility claim):
 *
 * <pre>
 *   gimle get deployments [name]
 *   gimle apply -f &lt;manifest.yaml&gt;
 *   gimle delete deployment &lt;name&gt;
 *   gimle get nodes
 *   gimle get node-assignments &lt;nodeId&gt;
 *   gimle get tenants [id]
 *   gimle set tenant &lt;id&gt; --max-memory-bytes N --max-cpu-millicores N --max-instances N
 *   gimle delete tenant &lt;id&gt;
 *   gimle get config &lt;tenantId&gt;
 *   gimle set config &lt;tenantId&gt; &lt;key&gt; &lt;value&gt; [--encrypted]
 *   gimle delete config &lt;tenantId&gt; &lt;key&gt;
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
    } catch (RuntimeException e) {
      // Not a CliException: something further downstream (most likely Json.parse on a malformed
      // response body -- the *Command classes don't wrap that call) threw. Report it the same clean
      // way rather than letting it escape as a raw stack trace.
      err.println("error: unexpected response from control plane: " + e.getMessage());
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

    if (server == null || server.isBlank()) {
      throw new CliException(
          "no control-plane server configured (pass --server host:port or set GIMLE_SERVER)");
    }
    if (positional.isEmpty()) {
      throw new CliException(usage());
    }

    ControlPlaneClient client = new ControlPlaneClient(server);
    String verb = positional.get(0);
    List<String> rest = positional.subList(1, positional.size());

    switch (verb) {
      case "apply" -> new DeploymentsCommand(client, output, out).apply(rest);
      case "get" -> handleGet(rest, client, output, out);
      case "set" -> handleSet(rest, client, output, out);
      case "delete" -> handleDelete(rest, client, output, out);
      default -> throw new CliException(usage());
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
      case "node", "nodes" -> new NodesCommand(client, output, out).list();
      case "node-assignments" ->
          new NodesCommand(client, output, out).assignments(requireOne(rest, "node-assignments"));
      case "tenant", "tenants" -> new TenantsCommand(client, output, out).get(rest);
      case "config" -> new ConfigCommand(client, output, out).list(requireOne(rest, "config"));
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
      case "tenant", "tenants" ->
          new TenantsCommand(client, output, out).delete(requireOne(rest, "tenant"));
      case "config" -> new ConfigCommand(client, output, out).delete(rest);
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
          apply -f <file.yaml>
          delete deployment <name>
          get nodes
          get node-assignments <nodeId>
          get tenants [id]
          set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
          delete tenant <id>
          get config <tenantId>
          set config <tenantId> <key> <value> [--encrypted]
          delete config <tenantId> <key>""";
  }
}
