package com.gimle.hugin.model;

/**
 * One column of a resource table: the header it prints and the dotted path it reads out of each
 * resource's own JSON.
 *
 * <p>{@code weight} is how much of the flexible width this column asks for relative to its
 * siblings, not a cell count -- the kinds differ too much for a fixed width to suit all of them,
 * and a table whose columns were sized per kind by hand would drift the moment a kind gained a
 * field.
 */
public record ResourceColumn(String header, String path, int weight) {

  public ResourceColumn {
    if (header == null || header.isBlank()) {
      throw new IllegalArgumentException("header must not be blank");
    }
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    if (weight < 1) {
      throw new IllegalArgumentException("weight must be at least 1");
    }
  }

  /** The ordinary case: one share of the flexible width. */
  public static ResourceColumn of(final String header, final String path) {
    return new ResourceColumn(header, path, 1);
  }

  public static ResourceColumn wide(final String header, final String path) {
    return new ResourceColumn(header, path, 2);
  }
}
