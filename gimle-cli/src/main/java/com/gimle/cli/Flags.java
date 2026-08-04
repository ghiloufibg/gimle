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

  static Flags parse(List<String> args, Set<String> booleanFlagNames) {
    return parse(args, booleanFlagNames, Set.of());
  }

  /**
   * {@code repeatableFlagNames} may appear more than once, accumulating into {@link
   * #getAll(String)} rather than each occurrence overwriting the last -- {@code set role}'s {@code
   * --permission}, repeated once per grant, is the one caller that needs this today.
   */
  static Flags parse(
      List<String> args, Set<String> booleanFlagNames, Set<String> repeatableFlagNames) {
    Flags flags = new Flags();
    int i = 0;
    while (i < args.size()) {
      String token = args.get(i);
      if (!token.startsWith("--")) {
        throw new CliException("unexpected argument: " + token);
      }
      if (booleanFlagNames.contains(token)) {
        flags.setBooleanFlags.add(token);
        i++;
        continue;
      }
      if (i + 1 >= args.size()) {
        throw new CliException(token + " requires a value");
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
