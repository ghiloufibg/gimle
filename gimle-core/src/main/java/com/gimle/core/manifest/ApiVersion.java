package com.gimle.core.manifest;

import com.gimle.core.exception.GimleManifestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that defines how a manifest's optional top-level {@code apiVersion:} field is
 * interpreted, shared by every {@code kind:}-dispatching manifest parser (the control plane's
 * workload-kind dispatch and the CLI's own {@code ArtifactSet} parsing alike) so defaulting and
 * error wording can never drift between them.
 *
 * <p>An absent {@code apiVersion} always means {@link #V1ALPHA1} -- a stable contract, not a
 * "latest" pointer: opting into {@link #V1} (or any future version) always requires declaring it
 * explicitly, so an unversioned manifest can never silently change meaning under its author.
 * Matching is exact and case-sensitive; a version selects a parse ruleset within a kind, never the
 * kind itself.
 */
public enum ApiVersion {
  V1ALPHA1("v1alpha1"),
  V1("v1");

  private final String token;

  ApiVersion(String token) {
    this.token = token;
  }

  public String token() {
    return token;
  }

  /**
   * Resolves {@code root}'s optional {@code apiVersion} field against the versions {@code kind}
   * supports: absent means {@link #V1ALPHA1}, present must match one of {@code supported} exactly.
   * A present-but-malformed value (non-string, blank) or an unsupported version is a loud {@link
   * GimleManifestException}, never a fallback to some other version.
   */
  public static ApiVersion of(Map<?, ?> root, String kind, Set<ApiVersion> supported) {
    Object value = root.get("apiVersion");
    if (value == null) {
      return V1ALPHA1;
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "'apiVersion' must be a non-blank string when present -- omit it entirely for the"
              + " kind's alpha version (v1alpha1)");
    }
    for (ApiVersion version : values()) {
      if (version.token.equals(s) && supported.contains(version)) {
        return version;
      }
    }
    throw new GimleManifestException(
        "unsupported apiVersion '"
            + s
            + "' for kind "
            + kind
            + " -- supported: "
            + supportedList(supported));
  }

  private static String supportedList(Set<ApiVersion> supported) {
    List<String> tokens = new ArrayList<>();
    // Enum declaration order, not the set's own iteration order, so the message is deterministic
    // and always lists the default (alpha) first.
    for (ApiVersion version : values()) {
      if (supported.contains(version)) {
        tokens.add(version == V1ALPHA1 ? version.token + " (default when omitted)" : version.token);
      }
    }
    return String.join(", ", tokens);
  }
}
