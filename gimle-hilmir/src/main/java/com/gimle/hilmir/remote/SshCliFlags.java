package com.gimle.hilmir.remote;

import java.util.Optional;

/**
 * {@code --remote} dispatch's own CLI overrides ({@code --ssh-user}/{@code --ssh-key}/{@code
 * --ssh-port}/{@code --install-dir}) -- the highest-precedence tier {@link
 * ResolvedSshTarget#resolve} consults, ahead of a topology's per-machine or topology-wide {@code
 * ssh:} settings.
 */
public record SshCliFlags(
    Optional<String> user,
    Optional<Integer> port,
    Optional<String> identityFile,
    Optional<String> installDir) {

  public static final SshCliFlags NONE =
      new SshCliFlags(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

  public SshCliFlags {
    if (user == null || port == null || identityFile == null || installDir == null) {
      throw new IllegalArgumentException("ssh cli flags fields must not be null");
    }
  }
}
