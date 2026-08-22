package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.Optional;

/**
 * One physical or virtual machine a topology can place processes on: its name, its host, an
 * optional pinned SSH host-key fingerprint (see {@code com.gimle.hilmir.remote.SshProcessExec}'s
 * known-hosts pinning -- unset means trust-on-first-use instead of a hard pin), and an optional
 * per-machine {@link SshSettings} override for {@code --remote} dispatch (see {@code
 * com.gimle.hilmir.remote.ResolvedSshTarget} for how it combines with {@link RuntimeSettings#ssh()}
 * and CLI flags).
 */
public record Machine(
    String name, String host, Optional<String> sshHostKeyFingerprint, Optional<SshSettings> ssh) {

  public Machine {
    if (name == null || name.isBlank()) {
      throw new GimleManifestException("machine name must be a non-blank string");
    }
    if (host == null || host.isBlank()) {
      throw new GimleManifestException("machine " + name + " must declare a non-blank host");
    }
    if (sshHostKeyFingerprint == null) {
      throw new GimleManifestException(
          "machine " + name + "'s sshHostKeyFingerprint field must not be null");
    }
    if (sshHostKeyFingerprint.isPresent() && sshHostKeyFingerprint.get().isBlank()) {
      throw new GimleManifestException(
          "machine " + name + "'s sshHostKeyFingerprint must be non-blank if present");
    }
    if (ssh == null) {
      throw new GimleManifestException("machine " + name + "'s ssh field must not be null");
    }
  }
}
