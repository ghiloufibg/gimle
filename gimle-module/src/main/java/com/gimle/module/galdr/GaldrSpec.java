package com.gimle.module.galdr;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed access into one custom resource's stored spec tree. The tree is already schema-validated
 * and defaulted by admission before it is ever stored, so a field the kind's schema declares with a
 * default (or as required) is always present with its declared type -- the getters here lean on
 * that guarantee and fail loudly, naming the field, if an operator asks for something its own
 * kind's schema never put there. {@link #optional(String)} is the accessor for a genuinely
 * optional, defaultless field.
 */
public final class GaldrSpec {

  private final Map<String, Object> tree;

  GaldrSpec(Map<String, Object> tree) {
    this.tree = Map.copyOf(tree);
  }

  public boolean has(String field) {
    return tree.containsKey(field);
  }

  public Optional<Object> optional(String field) {
    return Optional.ofNullable(tree.get(field));
  }

  public String getString(String field) {
    return (String) require(field);
  }

  public int getInt(String field) {
    return ((Number) require(field)).intValue();
  }

  public long getLong(String field) {
    return ((Number) require(field)).longValue();
  }

  public double getDouble(String field) {
    return ((Number) require(field)).doubleValue();
  }

  public boolean getBoolean(String field) {
    return (Boolean) require(field);
  }

  public List<Object> getList(String field) {
    return List.copyOf((List<?>) require(field));
  }

  /** A nested object field as its own {@link GaldrSpec}, for schema {@code object} fields. */
  @SuppressWarnings("unchecked") // the stored tree is schema-validated JSON: object values are maps
  public GaldrSpec getObject(String field) {
    return new GaldrSpec((Map<String, Object>) require(field));
  }

  /** The whole spec tree, verbatim -- for an operator that walks it generically. */
  public Map<String, Object> raw() {
    return tree;
  }

  private Object require(String field) {
    Object value = tree.get(field);
    if (value == null) {
      throw new IllegalStateException(
          "spec has no field '"
              + field
              + "' -- declared fields present: "
              + tree.keySet()
              + " (a required or defaulted field is always stored; use optional() for a"
              + " genuinely optional one)");
    }
    return value;
  }
}
