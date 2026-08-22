package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.Optional;

/**
 * SSH connection overrides for {@code --remote} dispatch: the login user, port, private key file,
 * the directory a platform archive is unpacked into on the target machine, and a local platform
 * archive to ship and unpack there when it isn't yet. Every field is optional and independently
 * overridable -- see {@link Machine#ssh()} (per-machine) and {@link RuntimeSettings#ssh()}
 * (topology-wide default) for the two places this can appear, and {@code
 * com.gimle.hilmir.remote.ResolvedSshTarget} for how the two combine (and, for every field but
 * {@code archive}, with CLI flags on top). A field left unset at every tier falls back to the
 * operator's own {@code ssh} configuration (agent, default key, {@code ~/.ssh/config}) rather than
 * a value this record invents -- {@code archive} is the one exception: provisioning fails outright,
 * naming the machine, if it's needed and unset at both tiers, rather than silently staying local.
 */
public record SshSettings(
    Optional<String> user,
    Optional<Integer> port,
    Optional<String> identityFile,
    Optional<String> installDir,
    Optional<String> archive) {

  public static final SshSettings EMPTY =
      new SshSettings(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

  public SshSettings {
    if (user == null
        || port == null
        || identityFile == null
        || installDir == null
        || archive == null) {
      throw new GimleManifestException("ssh settings fields must not be null");
    }
    if (user.isPresent() && user.get().isBlank()) {
      throw new GimleManifestException("ssh.user must be non-blank if present");
    }
    if (port.isPresent() && (port.get() < 1 || port.get() > 65535)) {
      throw new GimleManifestException("ssh.port must be between 1 and 65535 if present");
    }
    if (identityFile.isPresent() && identityFile.get().isBlank()) {
      throw new GimleManifestException("ssh.identityFile must be non-blank if present");
    }
    if (installDir.isPresent() && installDir.get().isBlank()) {
      throw new GimleManifestException("ssh.installDir must be non-blank if present");
    }
    if (archive.isPresent() && archive.get().isBlank()) {
      throw new GimleManifestException("ssh.archive must be non-blank if present");
    }
  }
}
