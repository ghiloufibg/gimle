package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;

/** A login account provisioned right after boot, before any scenario runs. */
public record AccountSeed(String username, String password) {

  public AccountSeed {
    if (username == null || username.isBlank()) {
      throw new GimleManifestException("seed account username must be a non-blank string");
    }
    if (password == null || password.isBlank()) {
      throw new GimleManifestException("seed account password must be a non-blank string");
    }
  }
}
