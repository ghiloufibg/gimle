package com.gimle.core.io;

import java.util.regex.Pattern;

/**
 * Allow-list for a name that ends up as one raw filesystem path segment -- a module's own declared
 * volume name, a workload/deployment name feeding a volume path, anything else joined onto a base
 * directory via {@code Path.resolve} without going through the operating system's own path
 * resolution first. No {@code /} or {@code \}, and no leading {@code .} (which rules out {@code ..}
 * without special-casing it), so a caller can never smuggle a path-traversal or absolute-path
 * segment into a name that was only ever validated as "not blank." Dots are otherwise permitted
 * since a dotted name (a JPMS module id, a semver-ish version string) is a legitimate segment on its
 * own.
 *
 * <p>The same allow-list {@code gimle-andvari}'s own {@code ArtifactStore} and {@code gimle-muninn}
 * already apply to their own path segments -- centralized here once a third caller ({@link
 * com.gimle.core.module.ModuleDescriptor}'s own volume names) needed the identical defense rather
 * than a fourth copy of the same regex.
 */
public final class PathSegmentNames {

  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private PathSegmentNames() {}

  /** Whether {@code value} is safe to use as one raw path segment, per this class's own allow-list. */
  public static boolean isValidSegment(String value) {
    return value != null && SEGMENT.matcher(value).matches();
  }

  /**
   * Throws {@link IllegalArgumentException} naming {@code label} unless {@link
   * #isValidSegment(String)} holds -- the common "validate at the boundary" shape every caller of
   * this class wants, so a name that fails this check is refused before it is ever joined onto a
   * real filesystem path.
   */
  public static void requireValidSegment(String value, String label) {
    if (!isValidSegment(value)) {
      throw new IllegalArgumentException("invalid " + label + ": " + value);
    }
  }
}
