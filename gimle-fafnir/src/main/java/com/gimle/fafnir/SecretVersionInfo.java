package com.gimle.fafnir;

/**
 * One stored version of a secret, described: which version number, who wrote it, when, and what
 * shape it was declared to have. {@code GET /secrets/{tenantId}/{key}/versions} used to answer with
 * bare integers, which left "who wrote version 3 of this key, and when" answerable only by
 * eyeballing timestamps against the cluster-wide audit ring buffer; this record is what lets the
 * version listing answer it directly.
 *
 * <p>Never carries the value itself -- this is the metadata half of the same {@code list} vs {@code
 * get} split {@link SecretMetadata} already draws.
 */
public record SecretVersionInfo(
    int version, String author, long writtenAtEpochMilli, SecretType type) {

  public SecretVersionInfo {
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive, got " + version);
    }
    author = author == null || author.isBlank() ? "unknown" : author;
    type = type == null ? SecretType.OPAQUE : type;
  }
}
