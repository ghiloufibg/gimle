package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;

/**
 * Utgard's own container fleet could not be started -- no Docker daemon was reachable, or starting
 * a container failed for any other reason (most notably, in a sandbox with no image-registry
 * egress, the base image pull itself). A distinct type from the general {@link HolmgangException}
 * so a suite's own {@code @BeforeAll} can catch exactly this and convert it into a clean JUnit
 * assumption-based skip, the same way {@code SurtrIT} skips when its own precondition is unmet,
 * rather than swallowing a genuine bug elsewhere in the fixture as if it were an environment
 * limitation.
 */
public final class UtgardDockerUnavailableException extends HolmgangException {

  public UtgardDockerUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
