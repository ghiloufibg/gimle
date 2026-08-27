package com.gimle.ragnarok.config;

import com.gimle.ragnarok.RagnarokException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The structural YAML validation helpers shared by every parser in this module ({@link
 * com.gimle.ragnarok.surtr.SurtrWorkloadParser}, {@link com.gimle.ragnarok.fenrir.ChaosPlanParser},
 * {@link com.gimle.ragnarok.target.endpoint.TargetSpecParser}): is this field present, is it the
 * right shape. Semantic rules (ranges, cross-field constraints, duplicates) stay out of here, in
 * each parsed record's own constructor -- the same split every parser in this codebase already
 * follows.
 */
public final class YamlParsing {

  private YamlParsing() {}

  public static String requireString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new RagnarokException("missing or blank required field: " + key);
    }
    return s;
  }

  public static Optional<String> optionalString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new RagnarokException("field must be a non-blank string if present: " + key);
    }
    return Optional.of(s);
  }

  public static Optional<Integer> optionalInt(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new RagnarokException("field must be numeric if present: " + key);
    }
    return Optional.of(number.intValue());
  }

  public static Optional<Long> optionalLong(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new RagnarokException("field must be numeric if present: " + key);
    }
    return Optional.of(number.longValue());
  }

  public static Optional<Double> optionalDouble(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new RagnarokException("field must be numeric if present: " + key);
    }
    return Optional.of(number.doubleValue());
  }

  public static Optional<Boolean> optionalBoolean(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Boolean b)) {
      throw new RagnarokException("field must be a boolean if present: " + key);
    }
    return Optional.of(b);
  }

  public static int requireInt(final Map<?, ?> map, final String key, final String prefix) {
    final Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new RagnarokException("missing or non-numeric required field: " + prefix + key);
    }
    return number.intValue();
  }

  public static List<String> stringList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new RagnarokException("'" + key + "' must be a list");
    }
    final List<String> strings = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new RagnarokException("each '" + key + "' entry must be a non-blank string");
      }
      strings.add(s);
    }
    return strings;
  }

  public static List<Map<?, ?>> mapList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new RagnarokException("'" + key + "' must be a list");
    }
    final List<Map<?, ?>> maps = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof Map<?, ?> m)) {
        throw new RagnarokException("each '" + key + "' entry must be a mapping");
      }
      maps.add(m);
    }
    return maps;
  }
}
