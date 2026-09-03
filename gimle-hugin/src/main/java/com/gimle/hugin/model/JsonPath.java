package com.gimle.hugin.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a dotted path out of a parsed JSON tree: {@code usage.instances}, {@code
 * quota.maxInstances}.
 *
 * <p>Every failure resolves to an empty cell rather than an exception, because the paths come from
 * two places that are both allowed to be wrong about a given response: this module's own column
 * definitions, and a custom kind's declared print columns, which are authored by whoever registered
 * the kind and can name a field an instance simply doesn't carry. A table that threw on the first
 * such path would take the whole screen down over one blank column.
 */
final class JsonPath {

  private JsonPath() {}

  /** The value at {@code path}, or {@code null} when any segment along the way is missing. */
  static Object valueAt(final Object root, final String path) {
    Object current = root;
    for (String segment : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(segment);
    }
    return current;
  }

  /**
   * The value at {@code path} as the text a table cell shows. A list renders as its comma-joined
   * elements when they are scalars and as a count otherwise -- a permission array printed in full
   * would be the only thing on the row -- and an object renders as a count of its own keys, since
   * the useful thing to say about one in a cell is whether it is there at all.
   */
  static String textAt(final Object root, final String path) {
    return text(valueAt(root, path));
  }

  static String text(final Object value) {
    return switch (value) {
      case null -> "";
      case String s -> s;
      case Boolean b -> b ? "yes" : "no";
      case Number n -> number(n);
      case List<?> list -> list(list);
      case Map<?, ?> map -> map.isEmpty() ? "" : map.size() + " fields";
      default -> String.valueOf(value);
    };
  }

  /** Whole numbers without a trailing {@code .0}: JSON has one number type, tables do not. */
  private static String number(final Number value) {
    double d = value.doubleValue();
    return d == Math.rint(d) && !Double.isInfinite(d)
        ? String.valueOf((long) d)
        : String.format(Locale.ROOT, "%.2f", d);
  }

  private static String list(final List<?> list) {
    if (list.isEmpty()) {
      return "";
    }
    if (list.stream().anyMatch(entry -> entry instanceof Map || entry instanceof List)) {
      return list.size() + (list.size() == 1 ? " entry" : " entries");
    }
    return String.join(",", list.stream().map(JsonPath::text).toList());
  }
}
