package com.gimle.andvari;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates between Andvari's own {@code (moduleId, version)} coordinates and the Maven-2
 * repository layout every jar registry from Central to Nexus serves. Gimlé module names are dotted
 * reverse-DNS JPMS names ({@code com.gimle.examples.greeter.provider}); the mapping is the obvious
 * one -- the last dot-segment is the Maven artifactId, everything before it is the groupId --
 * applied in both directions. A single-segment module name degenerates to an empty groupId.
 *
 * <p>The reverse mapping deliberately canonicalizes rather than forbids: two distinct Maven GAVs
 * can alias to one module coordinate (groupId {@code a.b} artifactId {@code c}, and groupId {@code
 * a} artifactId {@code b.c}, both resolve to module {@code a.b.c}). That's fine -- the store's own
 * immutability (a 409 on a differing re-push) is what actually enforces "one set of bytes per
 * coordinate", not the naming scheme feeding it.
 */
final class MavenCoordinates {

  private MavenCoordinates() {}

  /** One parsed {@code .../{artifactId}/{version}/{fileName}} tail. */
  record ArtifactFile(String moduleId, String artifactId, String version, String fileName) {}

  /**
   * Parses a Maven-2 repository tail (the request path after {@code /repository/}, no leading
   * slash) into a module coordinate plus the requested file name -- e.g. {@code
   * com/gimle/examples/greeter/provider/1.0.0/provider-1.0.0.jar} becomes module {@code
   * com.gimle.examples.greeter.provider}, artifactId {@code provider}, version {@code 1.0.0}, file
   * {@code provider-1.0.0.jar}. Returns {@code null} when the tail doesn't have the shape of a
   * versioned artifact file (fewer than three segments) -- that's a {@code maven-metadata.xml}
   * request one level up instead, see {@link #parseMetadataPath}.
   */
  static ArtifactFile parseArtifactFile(String tail) {
    List<String> segments = splitNonEmpty(tail);
    if (segments.size() < 3) {
      return null;
    }
    int n = segments.size();
    String fileName = segments.get(n - 1);
    String version = segments.get(n - 2);
    String artifactId = segments.get(n - 3);
    String moduleId = joinModuleId(segments.subList(0, n - 3), artifactId);
    return new ArtifactFile(moduleId, artifactId, version, fileName);
  }

  /** One parsed group-level {@code maven-metadata.xml} tail (no version segment). */
  record MetadataFile(String moduleId, String groupId, String artifactId, String fileName) {}

  /**
   * Parses a group-level {@code maven-metadata.xml} request tail, e.g. {@code
   * com/gimle/examples/greeter/provider/maven-metadata.xml} (or its {@code .sha1}/{@code .sha256}
   * sidecar). Returns {@code null} when the last segment isn't a {@code maven-metadata.xml}
   * variant, so the caller falls through to {@link #parseArtifactFile} instead.
   */
  static MetadataFile parseMetadataPath(String tail) {
    List<String> segments = splitNonEmpty(tail);
    if (segments.size() < 2) {
      return null;
    }
    int n = segments.size();
    String fileName = segments.get(n - 1);
    if (!fileName.equals("maven-metadata.xml")
        && !fileName.equals("maven-metadata.xml.sha1")
        && !fileName.equals("maven-metadata.xml.sha256")) {
      return null;
    }
    String artifactId = segments.get(n - 2);
    List<String> groupSegments = segments.subList(0, n - 2);
    String groupId = String.join(".", groupSegments);
    String moduleId = joinModuleId(groupSegments, artifactId);
    return new MetadataFile(moduleId, groupId, artifactId, fileName);
  }

  private static String joinModuleId(List<String> groupSegments, String artifactId) {
    return groupSegments.isEmpty()
        ? artifactId
        : String.join(".", groupSegments) + "." + artifactId;
  }

  private static List<String> splitNonEmpty(String tail) {
    List<String> segments = new ArrayList<>();
    for (String segment : tail.split("/")) {
      if (!segment.isBlank()) {
        segments.add(segment);
      }
    }
    return segments;
  }
}
