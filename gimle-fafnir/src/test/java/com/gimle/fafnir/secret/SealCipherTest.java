package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.fafnir.secret.SealCipher.SealedEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SealCipherTest {

  private static KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  @Test
  void seal_then_unseal_with_the_matching_aad_recovers_the_exact_plaintext() throws Exception {
    KeyPair keyPair = generateKeyPair();
    byte[] plaintext = "hunter2".getBytes(StandardCharsets.UTF_8);
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");

    SealedEnvelope envelope = SealCipher.seal(plaintext, keyPair.getPublic(), (byte) 3, aad);
    byte[] recovered = SealCipher.unseal(envelope, keyPair.getPrivate(), aad);

    assertArrayEquals(plaintext, recovered);
    assertEquals((byte) 3, envelope.sealingKeyId());
  }

  @Test
  void unseal_with_a_different_tenant_id_in_the_aad_is_rejected() throws Exception {
    KeyPair keyPair = generateKeyPair();
    byte[] plaintext = "hunter2".getBytes(StandardCharsets.UTF_8);
    SealedEnvelope envelope =
        SealCipher.seal(
            plaintext,
            keyPair.getPublic(),
            (byte) 0,
            SealCipher.aadFor("acme", "db-creds", "password"));

    assertThrows(
        IllegalStateException.class,
        () ->
            SealCipher.unseal(
                envelope,
                keyPair.getPrivate(),
                SealCipher.aadFor("globex", "db-creds", "password")));
  }

  @Test
  void unseal_with_a_different_name_in_the_aad_is_rejected() throws Exception {
    KeyPair keyPair = generateKeyPair();
    byte[] plaintext = "hunter2".getBytes(StandardCharsets.UTF_8);
    SealedEnvelope envelope =
        SealCipher.seal(
            plaintext,
            keyPair.getPublic(),
            (byte) 0,
            SealCipher.aadFor("acme", "db-creds", "password"));

    assertThrows(
        IllegalStateException.class,
        () ->
            SealCipher.unseal(
                envelope,
                keyPair.getPrivate(),
                SealCipher.aadFor("acme", "other-map", "password")));
  }

  @Test
  void unseal_with_a_different_key_in_the_aad_is_rejected() throws Exception {
    KeyPair keyPair = generateKeyPair();
    byte[] plaintext = "hunter2".getBytes(StandardCharsets.UTF_8);
    SealedEnvelope envelope =
        SealCipher.seal(
            plaintext,
            keyPair.getPublic(),
            (byte) 0,
            SealCipher.aadFor("acme", "db-creds", "password"));

    assertThrows(
        IllegalStateException.class,
        () ->
            SealCipher.unseal(
                envelope, keyPair.getPrivate(), SealCipher.aadFor("acme", "db-creds", "username")));
  }

  @Test
  void unseal_with_the_wrong_private_key_is_rejected() throws Exception {
    KeyPair sealer = generateKeyPair();
    KeyPair impostor = generateKeyPair();
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");
    SealedEnvelope envelope =
        SealCipher.seal("hunter2".getBytes(), sealer.getPublic(), (byte) 0, aad);

    assertThrows(
        IllegalStateException.class, () -> SealCipher.unseal(envelope, impostor.getPrivate(), aad));
  }

  @Test
  void unseal_of_a_corrupted_wrapped_key_is_rejected_cleanly() throws Exception {
    KeyPair keyPair = generateKeyPair();
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");
    SealedEnvelope envelope =
        SealCipher.seal("hunter2".getBytes(), keyPair.getPublic(), (byte) 0, aad);
    byte[] corruptedWrappedKey = envelope.wrappedDataKey().clone();
    corruptedWrappedKey[0] ^= 0xFF;
    SealedEnvelope corrupted =
        new SealedEnvelope(envelope.sealingKeyId(), corruptedWrappedKey, envelope.ciphertext());

    assertThrows(
        IllegalStateException.class, () -> SealCipher.unseal(corrupted, keyPair.getPrivate(), aad));
  }

  @Test
  void aad_for_different_field_boundaries_never_collides() {
    // "a" + ":" + "bc" would collide with "ab" + ":" + "c" under a naive delimiter join --
    // the length-prefixed encoding must keep these distinct.
    byte[] aad1 = SealCipher.aadFor("a", "bc", "d");
    byte[] aad2 = SealCipher.aadFor("ab", "c", "d");

    assertFalse(Arrays.equals(aad1, aad2));
  }
}
