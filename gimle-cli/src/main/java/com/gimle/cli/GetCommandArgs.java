package com.gimle.cli;

import java.util.List;
import java.util.Set;

/**
 * Splits a {@code get <kind> [name] [--flag <value> ...]}-style command's own remaining arguments
 * into an optional positional name and the flag tokens following it, treating a leading {@code
 * "--"}-prefixed token as "no name given" rather than as the name itself. Before this, every such
 * {@code get} command decided "list form vs. named lookup" by whether {@code args} was empty at
 * all, so a list-form call carrying only flags (e.g. {@code get deployments --tenant default}) had
 * its first flag token mistaken for the name.
 *
 * <p>Also validates every {@code "--"}-prefixed token against {@code recognizedFlags} -- the same
 * "unknown flag" discipline {@code cordon}/{@code uncordon} already apply to a stray trailing
 * argument -- instead of silently keeping an unrecognized one as the name (the pre-fix behavior
 * here) or, for a kind that takes no name at all, silently dropping it. A stray non-flag token
 * beyond the name itself is rejected the same "too many arguments" way {@link
 * GimleCli#requireAtMostOne} already rejects it for the resources built on that helper instead.
 * Every recognized flag accepted by a {@code get} command today is a valued one (e.g. {@code
 * --tenant <id>}), never a boolean switch, so the walk below always treats the token right after a
 * recognized flag as that flag's own value rather than a second flag.
 */
final class GetCommandArgs {

  private GetCommandArgs() {}

  /** {@code name} is {@code null} for the list form; {@code flagArgs} never includes it. */
  record Split(String name, List<String> flagArgs) {}

  static Split split(List<String> args, Set<String> recognizedFlags, String what, String usage) {
    boolean hasName = !args.isEmpty() && !args.get(0).startsWith("--");
    String name = hasName ? args.get(0) : null;
    List<String> flagArgs = hasName ? args.subList(1, args.size()) : args;
    validate(args, flagArgs, recognizedFlags, what, usage);
    return new Split(name, flagArgs);
  }

  /**
   * For a kind that takes no name at all ({@code get nodes}): every argument is a flag slot, so a
   * leading positional (non-{@code "--"}) token is rejected outright rather than mistaken for a
   * name nothing here has a place to put.
   */
  static List<String> splitNoName(List<String> args, Set<String> recognizedFlags, String usage) {
    if (!args.isEmpty() && !args.get(0).startsWith("--")) {
      throw new CliException("unexpected argument: " + args.get(0) + "\n\n" + usage);
    }
    validateFlagsOnly(args, recognizedFlags, usage);
    return args;
  }

  private static void validate(
      List<String> allArgs,
      List<String> flagArgs,
      Set<String> recognizedFlags,
      String what,
      String usage) {
    int i = 0;
    while (i < flagArgs.size()) {
      String token = flagArgs.get(i);
      if (!token.startsWith("--")) {
        throw tooManyArguments(what, allArgs);
      }
      i = consumeFlag(flagArgs, i, recognizedFlags, usage);
    }
  }

  private static void validateFlagsOnly(
      List<String> flagArgs, Set<String> recognizedFlags, String usage) {
    int i = 0;
    while (i < flagArgs.size()) {
      i = consumeFlag(flagArgs, i, recognizedFlags, usage);
    }
  }

  /**
   * Validates one flag token (already known to start with {@code "--"}) and returns the next index.
   */
  private static int consumeFlag(
      List<String> flagArgs, int i, Set<String> recognizedFlags, String usage) {
    String token = flagArgs.get(i);
    int equals = token.indexOf('=');
    String name = equals < 0 ? token : token.substring(0, equals);
    if (!recognizedFlags.contains(name)) {
      throw new CliException("unknown flag: " + token + "\n\n" + usage);
    }
    if (equals >= 0) {
      return i + 1;
    }
    if (i + 1 >= flagArgs.size()) {
      throw new CliException(token + " requires a value\n\n" + usage);
    }
    return i + 2;
  }

  private static CliException tooManyArguments(String what, List<String> args) {
    return new CliException(
        "too many arguments for "
            + what
            + ": expected at most one name/id, got "
            + args.size()
            + " ("
            + String.join(", ", args)
            + ")");
  }
}
