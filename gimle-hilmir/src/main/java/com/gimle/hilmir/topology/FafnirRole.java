package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Fafnir's declared replica placements and shared secret key file. Zero replicas parses fine --
 * {@code TopologyValidator}'s {@code NO_FAFNIR} rule flags it as an error, not the parser, since
 * {@code ControlPlaneMain} always requires exactly one {@code --fafnir-endpoint} at boot, not at
 * parse time. {@code keyFile} is a structural requirement rather than a validator rule, though: a
 * non-empty replica list with no key file is not a deployable-but-suboptimal topology the way an
 * empty replica list is, it is simply malformed input, so it is rejected here.
 */
public record FafnirRole(Optional<Path> keyFile, List<ServiceReplica> replicas) {

  public FafnirRole {
    if (keyFile == null) {
      throw new GimleManifestException("fafnir keyFile field must not be null");
    }
    replicas = List.copyOf(replicas);
    if (!replicas.isEmpty() && keyFile.isEmpty()) {
      throw new GimleManifestException(
          "fafnir.keyFile is required whenever fafnir.replicas is non-empty");
    }
  }
}
