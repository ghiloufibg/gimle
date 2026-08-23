package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SealingKeyRingTest {

  private static KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  @Test
  void active_key_pair_and_active_public_key_return_the_keypair_named_by_active_key_id()
      throws Exception {
    KeyPair pairZero = generateKeyPair();
    KeyPair pairOne = generateKeyPair();
    SealingKeyRing ring =
        new SealingKeyRing((byte) 1, Map.of((byte) 0, pairZero, (byte) 1, pairOne));

    assertSame(pairOne, ring.activeKeyPair());
    assertEquals(pairOne.getPublic(), ring.activePublicKey());
  }

  @Test
  void an_empty_keypairsbyid_map_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new SealingKeyRing((byte) 0, Map.of()));
  }

  @Test
  void an_active_key_id_with_no_corresponding_keypair_is_rejected() throws Exception {
    KeyPair pairZero = generateKeyPair();

    assertThrows(
        IllegalArgumentException.class,
        () -> new SealingKeyRing((byte) 5, Map.of((byte) 0, pairZero)));
  }
}
