package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A minimal {@code --flag value} / {@code --flag} (boolean) / repeatable {@code --flag value --flag
 * value ...} parser for one release verb's own arguments -- the same hand-rolled linear-scan shape
 * {@code HilmirMain}'s own flag helpers (see {@code requireFileFlag}, {@code machineFlag}) already
 * use, just reusable across the six release verbs' larger, shared flag vocabulary rather than one
 * bespoke scan per flag.
 */
final class ReleaseFlags {

  private final Map<String, String> values = new LinkedHashMap<>();
  private final Set<String> booleanFlags = new LinkedHashSet<>();
  private final Map<String, List<String>> repeatedValues = new LinkedHashMap<>();

  private ReleaseFlags() {}

  static ReleaseFlags parse(
      List<String> args, Set<String> booleanFlagNames, Set<String> repeatableFlagNames) {
    ReleaseFlags flags = new ReleaseFlags();
    int i = 0;
    while (i < args.size()) {
      String token = args.get(i);
      if (booleanFlagNames.contains(token)) {
        flags.booleanFlags.add(token);
        i++;
        continue;
      }
      if (i + 1 >= args.size()) {
        throw new HilmirException(token + " requires a value");
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

  boolean isSet(String flag) {
    return booleanFlags.contains(flag);
  }

  String get(String flag) {
    String value = values.get(flag);
    if (value == null) {
      throw new HilmirException("missing required flag: " + flag);
    }
    return value;
  }

  String getOrDefault(String flag, String defaultValue) {
    return values.getOrDefault(flag, defaultValue);
  }
}
