package com.gimle.hugin.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a parsed JSON tree into YAML lines for the describe pane.
 *
 * <p>Written here rather than pulled in from a YAML library because this module deliberately
 * carries one third-party dependency and only for something the JDK cannot do at all. This is not
 * that: the input is already a parsed tree of maps, lists and scalars, and what it has to produce
 * is a few dozen lines a human reads once. It is an emitter, never a parser -- nothing here is ever
 * read back, so the only thing it owes a reader is that what it prints says what the value is.
 *
 * <p>Quoting is therefore conservative: a scalar is quoted whenever leaving it bare could make it
 * read as something else -- a number, a boolean, null, empty, or a string carrying the punctuation
 * YAML itself gives meaning to.
 */
public final class Yaml {

  private static final int INDENT = 2;

  /** Values that must never be printed bare, because bare they mean something other than text. */
  private static final List<String> RESERVED =
      List.of("true", "false", "yes", "no", "on", "off", "null", "~");

  private Yaml() {}

  public static List<String> lines(final Object value) {
    List<String> lines = new ArrayList<>();
    if (value instanceof Map<?, ?> map && !map.isEmpty()) {
      appendMap(lines, map, 0);
    } else if (value instanceof List<?> list && !list.isEmpty()) {
      appendList(lines, list, 0);
    } else {
      lines.add(scalar(value));
    }
    return lines;
  }

  private static void appendMap(final List<String> lines, final Map<?, ?> map, final int depth) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = String.valueOf(entry.getKey()) + ":";
      appendEntry(lines, indent(depth) + key, entry.getValue(), depth);
    }
  }

  private static void appendList(final List<String> lines, final List<?> list, final int depth) {
    for (Object element : list) {
      if (isEmptyContainer(element) || !(element instanceof Map || element instanceof List)) {
        lines.add(indent(depth) + "- " + scalar(element));
        continue;
      }
      // A nested map's first key rides the dash so the element reads as one thing rather than as
      // an empty item followed by an unattached block.
      List<String> nested = new ArrayList<>();
      if (element instanceof Map<?, ?> map) {
        appendMap(nested, map, depth + 1);
      } else {
        appendList(nested, (List<?>) element, depth + 1);
      }
      lines.add(indent(depth) + "- " + nested.getFirst().stripLeading());
      lines.addAll(nested.subList(1, nested.size()));
    }
  }

  private static void appendEntry(
      final List<String> lines, final String prefix, final Object value, final int depth) {
    if (isEmptyContainer(value) || !(value instanceof Map || value instanceof List)) {
      lines.add(prefix + " " + scalar(value));
      return;
    }
    lines.add(prefix);
    if (value instanceof Map<?, ?> map) {
      appendMap(lines, map, depth + 1);
    } else {
      appendList(lines, (List<?>) value, depth + 1);
    }
  }

  private static boolean isEmptyContainer(final Object value) {
    return (value instanceof Map<?, ?> map && map.isEmpty())
        || (value instanceof List<?> list && list.isEmpty());
  }

  private static String scalar(final Object value) {
    return switch (value) {
      case null -> "null";
      case Boolean b -> String.valueOf(b);
      case Number n -> number(n);
      case Map<?, ?> ignored -> "{}";
      case List<?> ignored -> "[]";
      case String s -> string(s);
      default -> string(String.valueOf(value));
    };
  }

  /**
   * Whole numbers without a trailing {@code .0}: JSON has one number type, a reader expects two.
   */
  private static String number(final Number value) {
    double d = value.doubleValue();
    return d == Math.rint(d) && !Double.isInfinite(d)
        ? String.valueOf((long) d)
        : String.valueOf(d);
  }

  private static String string(final String value) {
    return needsQuoting(value) ? "\"" + escape(value) + "\"" : value;
  }

  private static boolean needsQuoting(final String value) {
    if (value.isEmpty()
        || !value.strip().equals(value)
        || RESERVED.contains(value.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (value.chars().anyMatch(c -> c == ':' || c == '#' || c == '"' || c == '\'' || c < ' ')) {
      return true;
    }
    if ("-?*&!|>%@`[]{},".indexOf(value.charAt(0)) >= 0) {
      return true;
    }
    // A string that would parse back as a number has to be quoted to stay a string.
    return value.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
  }

  private static String escape(final String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2);
    for (char character : value.toCharArray()) {
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\t' -> escaped.append("\\t");
        case '\r' -> escaped.append("\\r");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }

  private static String indent(final int depth) {
    return " ".repeat(depth * INDENT);
  }
}
