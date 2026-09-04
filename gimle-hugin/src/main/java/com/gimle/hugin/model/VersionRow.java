package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One revision of a config key, ConfigMap, secret or SecretMap.
 *
 * <p>{@code author} and {@code at} are empty wherever the ledger behind the kind does not record
 * them -- only the secret ledger does. Empty rather than invented: a blank column says "not
 * recorded", and a fabricated timestamp would say something false about who changed what and when.
 */
public record VersionRow(
    int version, Optional<String> author, Optional<Instant> at, String detail, boolean deleted) {

  public VersionRow {
    if (author == null || at == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    if (detail == null) {
      throw new IllegalArgumentException("detail must not be null");
    }
  }

  /** The text a filter is matched against: everything the row shows. */
  public String searchText() {
    return (version + " " + author.orElse("") + " " + detail + (deleted ? " deleted" : ""))
        .toLowerCase(Locale.ROOT);
  }
}
