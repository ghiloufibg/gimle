package com.gimle.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code context list|show|use|set|delete} -- names for the control planes this CLI talks to, so
 * moving between dev/staging/prod is {@code gimle context use staging} rather than a re-typed
 * {@code --server} on every command. Every subcommand here is purely local: nothing contacts a
 * control plane, so these are the only verbs that work with no server resolvable at all -- which is
 * exactly the state an operator is in before their first {@code context set}.
 *
 * <p>An explicit {@code --server} (and then {@code GIMLE_SERVER}) still outranks whatever is
 * selected here; see {@link ServerResolver}.
 */
public final class ContextCommand {

  private static final String SET_USAGE = "usage: gimle context set <name> --server host:port";

  private final OutputFormat.Kind output;
  private final PrintStream out;
  private final Path configPath;

  public ContextCommand(OutputFormat.Kind output, PrintStream out) {
    this(output, out, CliConfig.defaultPath());
  }

  ContextCommand(OutputFormat.Kind output, PrintStream out, Path configPath) {
    this.output = output;
    this.out = out;
    this.configPath = configPath;
  }

  /**
   * {@code serverFlag} is whatever the invocation's own global {@code --server} carried, already
   * stripped from {@code args} by the top-level flag parser -- {@code context set} is the one
   * subcommand that reads it as a value to store rather than as an address to dial.
   */
  public void run(List<String> args, String serverFlag) {
    String action = args.isEmpty() ? "list" : args.get(0);
    List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
    switch (action) {
      case "list" -> list(rest);
      case "show" -> show(rest);
      case "use" -> use(rest);
      case "set" -> set(rest, serverFlag);
      case "delete" -> delete(rest);
      default -> throw new CliException("unknown context action: " + action + "\n\n" + usage());
    }
  }

  private void list(List<String> args) {
    requireNoPositionals(args);
    CliConfig config = CliConfig.load(configPath);
    if (config.contexts().isEmpty() && output == OutputFormat.Kind.TABLE) {
      out.println(
          "no contexts configured in "
              + configPath
              + " -- create one with 'gimle context set <name> --server host:port'");
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CliContext context : config.contexts()) {
      rows.add(row(context, config.currentContext().orElse(null)));
    }
    OutputFormat.printList(output, rows, out);
  }

  private void show(List<String> args) {
    CliConfig config = CliConfig.load(configPath);
    String name =
        args.isEmpty() ? requireCurrentName(config) : GimleCli.requireAtMostOne(args, "context");
    CliContext context = config.find(name).orElseThrow(() -> unknown(name, config));
    OutputFormat.printObject(output, row(context, config.currentContext().orElse(null)), out);
  }

  private String requireCurrentName(CliConfig config) {
    return config
        .currentContext()
        .orElseThrow(
            () ->
                new CliException(
                    "no current context selected in "
                        + configPath
                        + " -- run 'gimle context use <name>'"));
  }

  private void use(List<String> args) {
    String name = requireName(args, "use");
    CliConfig config = CliConfig.load(configPath);
    CliContext context = config.find(name).orElseThrow(() -> unknown(name, config));
    config.withCurrentContext(name).save(configPath);
    OutputFormat.printResult(
        output,
        Map.of("kind", "context", "name", name, "server", context.server(), "current", true),
        "context/" + name + " selected (" + context.server() + ")",
        out);
  }

  private void set(List<String> args, String serverFlag) {
    if (args.isEmpty()) {
      throw new CliException(SET_USAGE);
    }
    String name = GimleCli.requireAtMostOne(args, "context");
    CliConfig.requireValidName(name);
    String server = requireServerAddress(serverFlag);
    CliConfig updated = CliConfig.load(configPath).withContext(new CliContext(name, server));
    updated.save(configPath);
    boolean current = updated.currentContext().filter(name::equals).isPresent();
    OutputFormat.printResult(
        output,
        Map.of("kind", "context", "name", name, "server", server, "current", current),
        "context/"
            + name
            + " set ("
            + server
            + ")"
            + (current ? " and selected as the current context" : ""),
        out);
  }

  private void delete(List<String> args) {
    String name = requireName(args, "delete");
    CliConfig config = CliConfig.load(configPath);
    config.find(name).orElseThrow(() -> unknown(name, config));
    boolean wasCurrent = config.currentContext().filter(name::equals).isPresent();
    config.withoutContext(name).save(configPath);
    OutputFormat.printResult(
        output,
        Map.of("kind", "context", "name", name, "deleted", true, "wasCurrent", wasCurrent),
        "context/"
            + name
            + " deleted"
            + (wasCurrent ? " (no current context selected any more)" : ""),
        out);
  }

  private static Map<String, Object> row(CliContext context, String currentName) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", context.name());
    row.put("server", context.server());
    row.put("current", context.name().equals(currentName));
    return row;
  }

  private static String requireName(List<String> args, String action) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle context " + action + " <name>");
    }
    return GimleCli.requireAtMostOne(args, "context");
  }

  private static void requireNoPositionals(List<String> args) {
    if (!args.isEmpty()) {
      throw new CliException("unexpected argument: " + args.get(0) + "\n\n" + usage());
    }
  }

  /**
   * The scheme is chosen by the transport configuration, not typed here -- a pasted {@code
   * http://host:port} would otherwise be stored and later concatenated into a nonsense URL.
   */
  private static String requireServerAddress(String value) {
    if (value == null || value.isBlank()) {
      throw new CliException(SET_USAGE);
    }
    if (value.contains("://")) {
      throw new CliException("--server takes a host:port, not a URL: " + value);
    }
    return value;
  }

  private CliException unknown(String name, CliConfig config) {
    List<String> known = config.contexts().stream().map(CliContext::name).toList();
    return new CliException(
        "no such context: "
            + name
            + (known.isEmpty()
                ? " (none are defined in " + configPath + ")"
                : " (known contexts: " + String.join(", ", known) + ")"));
  }

  static String usage() {
    return """
        usage: gimle context list
               gimle context show [name]
               gimle context use <name>
               gimle context set <name> --server host:port
               gimle context delete <name>""";
  }
}
