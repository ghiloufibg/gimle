package com.gimle.core.module;

import com.gimle.core.exception.GimleManifestException;

/**
 * What a stored artifact coordinate physically contains: a single runnable/loadable jar ({@link
 * #JAR}, every coordinate that predates this enum), or a zip archive of a whole multi-file
 * application directory ({@link #BUNDLE}) carrying its own {@code gimle-entrypoint.yaml} launch
 * descriptor at the archive root. A coordinate's kind is immutable once first pushed, exactly like
 * its bytes and its tenant -- every downstream consumer (cache unpacking, launch command
 * construction) branches on it, so a kind that changed mid-life under an already-cached copy would
 * be a correctness landmine.
 */
public enum ArtifactKind {
  JAR,
  BUNDLE;

  /** Parses the wire/persisted form; {@code null} or blank means {@link #JAR}. */
  public static ArtifactKind parse(String value) {
    if (value == null || value.isBlank()) {
      return JAR;
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("unknown artifact kind: " + value);
    }
  }
}
