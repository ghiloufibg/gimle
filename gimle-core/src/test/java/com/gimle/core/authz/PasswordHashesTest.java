package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PasswordHashesTest {

  @Test
  void hash_then_verify_round_trips() {
    byte[] hash = PasswordHashes.hash("correct horse battery staple".toCharArray());
    assertTrue(PasswordHashes.verify("correct horse battery staple".toCharArray(), hash));
  }

  @Test
  void verify_rejects_the_wrong_password() {
    byte[] hash = PasswordHashes.hash("correct horse battery staple".toCharArray());
    assertFalse(PasswordHashes.verify("wrong password".toCharArray(), hash));
  }

  @Test
  void two_hashes_of_the_same_password_differ_because_of_the_random_salt() {
    byte[] first = PasswordHashes.hash("same password".toCharArray());
    byte[] second = PasswordHashes.hash("same password".toCharArray());
    assertNotEquals(Arrays.toString(first), Arrays.toString(second));
    assertTrue(PasswordHashes.verify("same password".toCharArray(), first));
    assertTrue(PasswordHashes.verify("same password".toCharArray(), second));
  }

  @Test
  void verify_rejects_a_truncated_hash_instead_of_throwing() {
    assertFalse(PasswordHashes.verify("anything".toCharArray(), new byte[] {1, 2, 3}));
  }
}
