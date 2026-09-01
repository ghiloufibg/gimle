package com.gimle.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides which control plane an invocation talks to, in one fixed order: the {@code --server}
 * flag, then the {@code GIMLE_SERVER} environment variable, then the current context in the CLI's
 * own config file. The order never varies with what happens to be set -- an explicit flag always
 * wins over an inherited environment, which always wins over a stored default, so "which cluster
 * did that command actually hit" is answerable from the command line alone.
 *
 * <p>Each source is consulted only if every earlier one is absent, which is also what keeps a
 * broken config file from breaking unrelated commands: a {@code --server} invocation never reads
 * the file at all, and one that does read it and finds it unusable degrades to "no context
 * configured" with a warning on stderr rather than failing outright.
 */
final class ServerResolver {

  private ServerResolver() {}

  static String resolve(String serverFlag, String environmentValue, PrintStream err) {
    return resolve(serverFlag, environmentValue, CliConfig.defaultPath(), err);
  }

  static String resolve(
      String serverFlag, String environmentValue, Path configPath, PrintStream err) {
    if (isPresent(serverFlag)) {
      return serverFlag;
    }
    if (isPresent(environmentValue)) {
      return environmentValue;
    }
    return fromCurrentContext(configPath, err)
        .orElseThrow(
            () ->
                new CliException(
                    "no control-plane server configured (pass --server host:port, set"
                        + " GIMLE_SERVER, or select a context with 'gimle context use <name>')"));
  }

  private static Optional<String> fromCurrentContext(Path configPath, PrintStream err) {
    CliConfig config;
    try {
      config = CliConfig.load(configPath);
    } catch (CliException e) {
      err.println("warning: ignoring " + configPath + ": " + e.getMessage());
      return Optional.empty();
    }
    Optional<String> selected = config.currentContext();
    if (selected.isEmpty()) {
      return Optional.empty();
    }
    Optional<CliContext> context = config.find(selected.get());
    if (context.isEmpty()) {
      err.println(
          "warning: current context '"
              + selected.get()
              + "' is not defined in "
              + configPath
              + " -- run 'gimle context use <name>' to select an existing one");
      return Optional.empty();
    }
    return Optional.of(context.get().server());
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
