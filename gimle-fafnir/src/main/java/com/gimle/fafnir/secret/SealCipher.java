package com.gimle.fafnir.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * The sealing envelope: a fresh, single-use AES-256 data key encrypts the plaintext via the
 * *existing* {@link SecretCipher} unchanged, and the data key itself is wrapped under an RSA public
 * key with RSA-OAEP. The wrap step is where {@code (tenantId, name, key)} gets bound in -- as the
 * OAEP label ({@link PSource.PSpecified}), not as {@code SecretCipher} AAD, since {@code
 * SecretCipher} has no AAD parameter to add without breaking "reuse it unchanged." A label mismatch
 * at {@link #unseal} makes {@link Cipher#unwrap} throw before any plaintext is ever recovered -- a
 * caller can't produce a blob that unseals under a different destination than the one it was sealed
 * for, a property OAEP's label exists specifically to provide, not something built for this
 * occasion.
 */
public final class SealCipher {

  private static final String WRAP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
  private static final byte SEALED_DATA_KEY_ID = 0; // sentinel -- never looked up in a KeyRing

  private SealCipher() {}

  /**
   * One sealed value: {@code sealingKeyId} names which RSA keypair can unwrap {@code
   * wrappedDataKey}.
   */
  public record SealedEnvelope(byte sealingKeyId, byte[] wrappedDataKey, byte[] ciphertext) {}

  public static SealedEnvelope seal(
      byte[] plaintext, PublicKey publicKey, byte sealingKeyId, byte[] aad) {
    SecretKey dataKey = generateDataKey();
    byte[] ciphertext = SecretCipher.encrypt(plaintext, dataKey, SEALED_DATA_KEY_ID);
    byte[] wrappedDataKey = wrap(dataKey, publicKey, aad);
    return new SealedEnvelope(sealingKeyId, wrappedDataKey, ciphertext);
  }

  /**
   * Recovers the plaintext: unwraps the data key under {@code privateKey} with the same {@code aad}
   * label {@link #seal} used, then decrypts the payload via {@link SecretCipher#decrypt(byte[],
   * SecretKey)} -- the data key is already in hand from the unwrap, no {@code KeyRing} lookup
   * needed. Throws on a label mismatch, the wrong private key, or a corrupt blob; never partially
   * succeeds.
   */
  public static byte[] unseal(SealedEnvelope envelope, PrivateKey privateKey, byte[] aad) {
    SecretKey dataKey = unwrap(envelope.wrappedDataKey(), privateKey, aad);
    return SecretCipher.decrypt(envelope.ciphertext(), dataKey);
  }

  /**
   * A length-prefixed, delimiter-free encoding of {@code (tenantId, name, key)} for the OAEP label
   * -- a plain {@code tenantId + ":" + name + ":" + key} join would let two different triples
   * canonicalize to the same bytes if {@code tenantId} (unlike {@code name}/{@code key}, which
   * {@code SecretMapCodec} already forbids containing {@code :}) ever contained the separator,
   * silently widening what a sealed blob could be replayed against.
   */
  public static byte[] aadFor(String tenantId, String name, String key) {
    byte[] tenantIdBytes = tenantId.getBytes(StandardCharsets.UTF_8);
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[12 + tenantIdBytes.length + nameBytes.length + keyBytes.length];
    int offset = 0;
    offset = writeLengthPrefixed(result, offset, tenantIdBytes);
    offset = writeLengthPrefixed(result, offset, nameBytes);
    writeLengthPrefixed(result, offset, keyBytes);
    return result;
  }

  private static int writeLengthPrefixed(byte[] dest, int offset, byte[] field) {
    dest[offset] = (byte) (field.length >>> 24);
    dest[offset + 1] = (byte) (field.length >>> 16);
    dest[offset + 2] = (byte) (field.length >>> 8);
    dest[offset + 3] = (byte) field.length;
    System.arraycopy(field, 0, dest, offset + 4, field.length);
    return offset + 4 + field.length;
  }

  private static byte[] wrap(SecretKey dataKey, PublicKey publicKey, byte[] aad) {
    try {
      Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
      cipher.init(Cipher.WRAP_MODE, publicKey, oaepSpec(aad));
      return cipher.wrap(dataKey);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("sealing data key wrap failed", e);
    }
  }

  private static SecretKey unwrap(byte[] wrappedDataKey, PrivateKey privateKey, byte[] aad) {
    try {
      Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
      cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec(aad));
      return (SecretKey) cipher.unwrap(wrappedDataKey, "AES", Cipher.SECRET_KEY);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("sealed data key unwrap failed", e);
    }
  }

  private static OAEPParameterSpec oaepSpec(byte[] aad) {
    return new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, new PSource.PSpecified(aad));
  }

  private static SecretKey generateDataKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES data key generation unavailable", e);
    }
  }
}
