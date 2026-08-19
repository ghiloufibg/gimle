package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.Optional;

/**
 * One physical or virtual machine a topology can place processes on: its name, its host, and an
 * optional per-machine {@link SshSettings} override for {@code --remote} dispatch (see {@code
 * com.gimle.hilmir.remote.ResolvedSshTarget} for how it combines with {@link RuntimeSettings#ssh()}
 * and CLI flags).
 */
public record Machine(String name, String host, Optional<SshSettings> ssh) {

  public Machine {
    if (name == null || name.isBlank()) {
      throw new GimleManifestException("machine name must be a non-blank string");
    }
    if (host == null || host.isBlank()) {
      throw new GimleManifestException("machine " + name + " must declare a non-blank host");
    }
    if (ssh == null) {
      throw new GimleManifestException("machine " + name + "'s ssh field must not be null");
    }
  }

  /** Preserves every pre-existing 2-arg call site: a machine with no per-machine ssh override. */
  public Machine(final String name, final String host) {
    this(name, host, Optional.empty());
  }
}
