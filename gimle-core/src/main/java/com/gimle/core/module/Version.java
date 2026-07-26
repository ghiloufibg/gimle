package com.gimle.core.module;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A semver-shaped version: {@code major.minor.patch[-qualifier]}. */
public record Version(int major, int minor, int patch, String qualifier)
    implements Comparable<Version> {

  private static final Pattern PATTERN =
      Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?$");

  public Version {
    if (major < 0 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException(
          "version components must be non-negative: " + major + "." + minor + "." + patch);
    }
    if (qualifier == null) {
      throw new IllegalArgumentException("qualifier must be an empty string, not null");
    }
  }

  public static Version parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("version must not be null");
    }
    String trimmed = text.strip();
    Matcher matcher = PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "malformed version (expected major.minor.patch[-qualifier]): " + text);
    }
    int major = Integer.parseInt(matcher.group(1));
    int minor = Integer.parseInt(matcher.group(2));
    int patch = Integer.parseInt(matcher.group(3));
    String qualifier = matcher.group(4);
    return new Version(major, minor, patch, qualifier == null ? "" : qualifier);
  }

  @SuppressWarnings("checkstyle:MethodName")
  @Override
  public int compareTo(Version other) {
    int cmp = Integer.compare(major, other.major);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(minor, other.minor);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(patch, other.patch);
    if (cmp != 0) {
      return cmp;
    }
    return compare_qualifier(qualifier, other.qualifier);
  }

  private static int compare_qualifier(String a, String b) {
    // No qualifier outranks any qualifier (1.0.0 > 1.0.0-rc1) — semver precedence.
    boolean aEmpty = a.isEmpty();
    boolean bEmpty = b.isEmpty();
    if (aEmpty && bEmpty) {
      return 0;
    }
    if (aEmpty) {
      return 1;
    }
    if (bEmpty) {
      return -1;
    }
    return a.compareTo(b);
  }

  @SuppressWarnings("checkstyle:MethodName")
  @Override
  public String toString() {
    String base = major + "." + minor + "." + patch;
    return qualifier.isEmpty() ? base : base + "-" + qualifier;
  }
}
