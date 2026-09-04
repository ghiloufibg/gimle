package com.gimle.hugin.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What one resource kind answered, verb by verb.
 *
 * <p>A verb missing from the map is one the control plane did not answer for, which is deliberately
 * not the same as a denial: a cell nobody could ask about must not be drawn as "no", or a grid
 * produced by a half-failed read would read as a much narrower account than the caller actually
 * has.
 */
public record PermissionRow(String kind, Map<String, Boolean> allowedByVerb) {

  public PermissionRow {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind must not be blank");
    }
    allowedByVerb = Map.copyOf(allowedByVerb);
  }

  /** Empty when the question was never answered, rather than answered "no". */
  public Optional<Boolean> allowed(final String verb) {
    return Optional.ofNullable(allowedByVerb.get(verb));
  }

  /** Whether anything at all is permitted here -- what decides if the row is worth looking at. */
  public boolean anyAllowed() {
    return allowedByVerb.containsValue(Boolean.TRUE);
  }

  /** The text a filter is matched against: the kind, plus the verbs actually granted. */
  public String searchText() {
    StringBuilder text = new StringBuilder(kind);
    allowedByVerb.entrySet().stream()
        .filter(Map.Entry::getValue)
        .forEach(entry -> text.append(' ').append(entry.getKey()));
    return text.toString().toLowerCase(Locale.ROOT);
  }
}
