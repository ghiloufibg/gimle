package com.gimle.hilmir.extension;

import com.gimle.hilmir.HilmirException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The same minimal {@code --flag value} / {@code --flag} (boolean) / repeatable {@code --flag value
 * --flag value ...} linear-scan parser {@code com.gimle.hilmir.release.ReleaseFlags} already is for
 * the six release verbs, duplicated here rather than depended on: that class is package-private to
 * {@code com.gimle.hilmir.release}, and this module's own convention (see {@code
 * ControlPlaneApi}/{@code HilmirException}) is to duplicate a small piece like this one rather than
 * widen a package boundary or add a cross-module dependency for it.
 */
final class ExtensionFlags {

  private final Map<String, String> values = new LinkedHashMap<>();
  private final Set<String> booleanFlags = new LinkedHashSet<>();
  private final Map<String, List<String>> repeatedValues = new LinkedHashMap<>();

  private ExtensionFlags() {}

  static ExtensionFlags parse(
      List<String> args, Set<String> booleanFlagNames, Set<String> repeatableFlagNames) {
    ExtensionFlags flags = new ExtensionFlags();
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

  String getOrDefault(String flag, String defaultValue) {
    return values.getOrDefault(flag, defaultValue);
  }
}
