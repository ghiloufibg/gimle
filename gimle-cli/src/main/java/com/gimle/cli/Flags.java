package com.gimle.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A minimal {@code --flag value} / {@code --flag} (boolean) / repeatable {@code --flag value --flag
 * value ...} parser for a single command's own arguments.
 */
final class Flags {

  private final Map<String, String> values = new LinkedHashMap<>();
  private final Set<String> setBooleanFlags = new LinkedHashSet<>();
  private final Map<String, List<String>> repeatedValues = new LinkedHashMap<>();

  private Flags() {}

  static Flags parse(List<String> args, Set<String> booleanFlagNames, String usage) {
    return parse(args, booleanFlagNames, Set.of(), usage);
  }

  /**
   * The strict counterpart to {@link #parse}: every {@code --flag} must appear in {@code
   * recognizedFlagNames} (boolean and repeatable names included), and one that doesn't is refused
   * rather than parsed into a map nothing ever reads. Without this a stray flag -- a real one
   * borrowed from a sibling verb, say {@code --version} on a write -- was accepted in silence, so a
   * caller who believed it had scoped their write got no signal at all that it hadn't.
   */
  static Flags parseKnown(
      List<String> args,
      Set<String> booleanFlagNames,
      Set<String> repeatableFlagNames,
      Set<String> recognizedFlagNames,
      String usage) {
    for (String token : args) {
      if (!token.startsWith("--")) {
        continue;
      }
      int equals = token.indexOf('=');
      String name = equals >= 0 ? token.substring(0, equals) : token;
      if (!recognizedFlagNames.contains(name)) {
        throw new CliException("unknown flag: " + name + "\n\n" + usage);
      }
    }
    return parse(args, booleanFlagNames, repeatableFlagNames, usage);
  }

  /** {@link #parseKnown} for the common case of no boolean and no repeatable flags. */
  static Flags parseKnown(List<String> args, Set<String> recognizedFlagNames, String usage) {
    return parseKnown(args, Set.of(), Set.of(), recognizedFlagNames, usage);
  }

  /**
   * {@code repeatableFlagNames} may appear more than once, accumulating into {@link
   * #getAll(String)} rather than each occurrence overwriting the last -- {@code set role}'s {@code
   * --permission}, repeated once per grant, is the one caller that needs this today.
   *
   * <p>{@code usage} is the calling command's own usage string, appended to either failure below
   * the same way every hand-written "too few arguments" check elsewhere in this package already
   * appends it (see {@code LogsCommand}'s own {@code "unknown flag: " + arg + "\n\n" + usage()}) --
   * this parser has no notion of which command invoked it, so without a caller-supplied usage
   * string a caller's own stray argument (a natural mistake: a value passed positionally where a
   * flag was expected) surfaced only a bare "unexpected argument: ...", the one failure mode in
   * this whole CLI that gave no hint of the correct syntax.
   */
  static Flags parse(
      List<String> args,
      Set<String> booleanFlagNames,
      Set<String> repeatableFlagNames,
      String usage) {
    Flags flags = new Flags();
    int i = 0;
    while (i < args.size()) {
      String token = args.get(i);
      if (!token.startsWith("--")) {
        throw new CliException("unexpected argument: " + token + "\n\n" + usage);
      }
      // A single --flag=value token carries its own value inline, the same syntax getopt-style
      // parsers accept alongside the space-separated form -- an operator reaching for either
      // spelling of "give this flag a value" should never be told the flag doesn't exist.
      int equals = token.indexOf('=');
      if (equals >= 0) {
        String name = token.substring(0, equals);
        String value = token.substring(equals + 1);
        if (booleanFlagNames.contains(name)) {
          throw new CliException(name + " does not take a value\n\n" + usage);
        }
        if (repeatableFlagNames.contains(name)) {
          flags.repeatedValues.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        } else {
          flags.values.put(name, value);
        }
        i++;
        continue;
      }
      if (booleanFlagNames.contains(token)) {
        flags.setBooleanFlags.add(token);
        i++;
        continue;
      }
      if (i + 1 >= args.size()) {
        throw new CliException(token + " requires a value\n\n" + usage);
      }
      String value = args.get(i + 1);
      if (repeatableFlagNames.contains(token)) {
        flags.repeatedValues.computeIfAbsent(token, k -> new ArrayList<>()).add(value);
      } else {
        flags.values.put(token, value);
      }
      i += 2;
    }
    return flags;
  }

  List<String> getAll(String flag) {
    return repeatedValues.getOrDefault(flag, List.of());
  }

  long requireLong(String flag) {
    String value = values.get(flag);
    if (value == null) {
      throw new CliException("missing required flag: " + flag);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new CliException(flag + " must be a number: " + value);
    }
  }

  boolean isSet(String flag) {
    return setBooleanFlags.contains(flag);
  }

  String get(String flag) {
    String value = values.get(flag);
    if (value == null) {
      throw new CliException("missing required flag: " + flag);
    }
    return value;
  }

  String getOrDefault(String flag, String defaultValue) {
    return values.getOrDefault(flag, defaultValue);
  }
}
