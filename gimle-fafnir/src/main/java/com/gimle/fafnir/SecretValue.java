package com.gimle.fafnir;

/**
 * One secret's decrypted current value together with the {@link SecretVersionInfo} describing the
 * version it came from -- what {@link SecretStore#getMany} returns per requested key, so a bulk
 * export carries each value's version, author, and declared type rather than a bare blob that says
 * nothing about what it is or where it came from.
 *
 * <p>Defensively copies {@code value} in both directions, the same posture {@code ConfigEntry}
 * takes: a decrypted plaintext array must never be aliased into a caller's own mutable state.
 */
public record SecretValue(byte[] value, SecretVersionInfo info) {

  public SecretValue {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (info == null) {
      throw new IllegalArgumentException("info must not be null");
    }
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }
}
