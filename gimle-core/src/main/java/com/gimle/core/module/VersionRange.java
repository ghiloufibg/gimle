package com.gimle.core.module;

/**
 * A version constraint on a {@link Requirement}. Maven/OSGi interval notation: {@code
 * [1.0.0,2.0.0)} inclusive-lower/exclusive-upper, {@code [1.5.0,)} unbounded-above, {@code 1.2.3}
 * exact.
 */
public sealed interface VersionRange permits VersionRange.ExactVersion, VersionRange.BoundedRange {

  boolean satisfies(Version candidate);

  static VersionRange parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("version range must not be null");
    }
    String trimmed = text.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("version range must not be empty");
    }
    char first = trimmed.charAt(0);
    if (first == '[' || first == '(') {
      return parse_bounded(trimmed);
    }
    return new ExactVersion(Version.parse(trimmed));
  }

  private static VersionRange parse_bounded(String text) {
    char lastChar = text.charAt(text.length() - 1);
    if (lastChar != ')' && lastChar != ']') {
      throw new IllegalArgumentException(
          "malformed version range (missing closing bracket): " + text);
    }
    boolean lowerInclusive = text.charAt(0) == '[';
    boolean upperInclusive = lastChar == ']';
    String inner = text.substring(1, text.length() - 1);
    int commaIdx = inner.indexOf(',');
    if (commaIdx < 0) {
      throw new IllegalArgumentException("malformed version range (expected a comma): " + text);
    }
    String lowerText = inner.substring(0, commaIdx).strip();
    String upperText = inner.substring(commaIdx + 1).strip();
    if (lowerText.isEmpty()) {
      throw new IllegalArgumentException("malformed version range (missing lower bound): " + text);
    }
    Version lower = Version.parse(lowerText);
    Version upper = upperText.isEmpty() ? null : Version.parse(upperText);
    return new BoundedRange(lower, lowerInclusive, upper, upperInclusive);
  }

  record ExactVersion(Version version) implements VersionRange {

    public ExactVersion {
      if (version == null) {
        throw new IllegalArgumentException("version must not be null");
      }
    }

    @Override
    public boolean satisfies(Version candidate) {
      return version.equals(candidate);
    }

    @Override
    public String toString() {
      return version.toString();
    }
  }

  /** {@code upper == null} means unbounded above. */
  record BoundedRange(Version lower, boolean lowerInclusive, Version upper, boolean upperInclusive)
      implements VersionRange {

    public BoundedRange {
      if (lower == null) {
        throw new IllegalArgumentException("lower bound must not be null");
      }
      if (upper != null && lower.compareTo(upper) > 0) {
        throw new IllegalArgumentException(
            "lower bound must not exceed upper bound: " + lower + " > " + upper);
      }
    }

    @Override
    public boolean satisfies(Version candidate) {
      int lowerCmp = candidate.compareTo(lower);
      boolean lowerOk = lowerInclusive ? lowerCmp >= 0 : lowerCmp > 0;
      if (!lowerOk) {
        return false;
      }
      if (upper == null) {
        return true;
      }
      int upperCmp = candidate.compareTo(upper);
      return upperInclusive ? upperCmp <= 0 : upperCmp < 0;
    }

    @Override
    public String toString() {
      String lowerBracket = lowerInclusive ? "[" : "(";
      String upperBracket = upperInclusive ? "]" : ")";
      String upperText = upper == null ? "" : upper.toString();
      return lowerBracket + lower + "," + upperText + upperBracket;
    }
  }
}
