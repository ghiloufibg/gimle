package com.gimle.fafnir;

/**
 * Everything a secret write records about itself beyond the value: who is writing it and what shape
 * the value is declared to have. Carried as one record rather than two more parameters on {@link
 * SecretStore#put} so that the write path (and {@code com.gimle.fafnir.secretmap.SecretMapStore},
 * which relays it straight through) doesn't grow a new parameter every time a version learns to
 * remember one more thing about how it came to exist.
 */
public record SecretWrite(String author, SecretType type) {

  public SecretWrite {
    author = author == null || author.isBlank() ? "unknown" : author;
    type = type == null ? SecretType.OPAQUE : type;
  }

  /**
   * The write shape for material with no declared type -- every SecretMap member key, and any flat
   * secret written without {@code --type}.
   */
  public static SecretWrite opaqueBy(String author) {
    return new SecretWrite(author, SecretType.OPAQUE);
  }
}
