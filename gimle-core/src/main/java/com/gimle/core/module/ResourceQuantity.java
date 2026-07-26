package com.gimle.core.module;

/**
 * Kubernetes-shaped resource quantity parsing: binary/decimal memory suffixes (Ki/Mi/Gi/Ti,
 * K/M/G/T) into bytes, and CPU either as fractional cores ("0.5") or millicores ("250m") into
 * milli-cores. Package-private: {@link ResourceSpec} is the public surface.
 */
final class ResourceQuantity {

  private ResourceQuantity() {}

  static long parse_memory(String text) {
    String trimmed = text.strip();
    if (trimmed.endsWith("Ki")) {
      return suffixed(trimmed, "Ki", 1024L, text);
    }
    if (trimmed.endsWith("Mi")) {
      return suffixed(trimmed, "Mi", 1024L * 1024, text);
    }
    if (trimmed.endsWith("Gi")) {
      return suffixed(trimmed, "Gi", 1024L * 1024 * 1024, text);
    }
    if (trimmed.endsWith("Ti")) {
      return suffixed(trimmed, "Ti", 1024L * 1024 * 1024 * 1024, text);
    }
    if (trimmed.endsWith("K")) {
      return suffixed(trimmed, "K", 1_000L, text);
    }
    if (trimmed.endsWith("M")) {
      return suffixed(trimmed, "M", 1_000_000L, text);
    }
    if (trimmed.endsWith("G")) {
      return suffixed(trimmed, "G", 1_000_000_000L, text);
    }
    if (trimmed.endsWith("T")) {
      return suffixed(trimmed, "T", 1_000_000_000_000L, text);
    }
    return parse_positive_long(trimmed, text);
  }

  static long parse_cpu(String text) {
    String trimmed = text.strip();
    if (trimmed.endsWith("m")) {
      return parse_positive_long(trimmed.substring(0, trimmed.length() - 1), text);
    }
    double cores;
    try {
      cores = Double.parseDouble(trimmed);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("malformed cpu quantity: " + text, e);
    }
    if (cores <= 0) {
      throw new IllegalArgumentException("cpu quantity must be positive: " + text);
    }
    return Math.round(cores * 1000);
  }

  private static long suffixed(String trimmed, String suffix, long multiplier, String original) {
    String numberPart = trimmed.substring(0, trimmed.length() - suffix.length());
    return parse_positive_long(numberPart, original) * multiplier;
  }

  private static long parse_positive_long(String numberPart, String original) {
    long value;
    try {
      value = Long.parseLong(numberPart.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("malformed quantity: " + original, e);
    }
    if (value <= 0) {
      throw new IllegalArgumentException("quantity must be positive: " + original);
    }
    return value;
  }
}
