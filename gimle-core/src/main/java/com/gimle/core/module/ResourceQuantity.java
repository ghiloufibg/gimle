package com.gimle.core.module;

/**
 * Kubernetes-shaped resource quantity parsing: binary/decimal memory suffixes (Ki/Mi/Gi/Ti,
 * K/M/G/T) into bytes, and CPU either as fractional cores ("0.5") or millicores ("250m") into
 * milli-cores, plus the inverse rendering used by diagnostics that only ever hold a computed number
 * (free capacity, a shortfall, a quota overage) rather than a manifest's own text. Package-private:
 * {@link ResourceSpec} is the public surface.
 */
final class ResourceQuantity {

  private static final long KIB = 1024L;
  private static final long MIB = 1024L * 1024;
  private static final long GIB = 1024L * 1024 * 1024;
  private static final long TIB = 1024L * 1024 * 1024 * 1024;

  private ResourceQuantity() {}

  /**
   * Renders {@code bytes} back into the largest binary suffix that divides it exactly, falling back
   * to a bare byte count -- so the text always round-trips through {@link #parseMemory} and a
   * diagnostic reads in the same units an operator writes in a manifest.
   */
  static String formatMemory(long bytes) {
    if (bytes == 0) {
      return "0";
    }
    String sign = bytes < 0 ? "-" : "";
    long magnitude = Math.abs(bytes);
    if (magnitude % TIB == 0) {
      return sign + (magnitude / TIB) + "Ti";
    }
    if (magnitude % GIB == 0) {
      return sign + (magnitude / GIB) + "Gi";
    }
    if (magnitude % MIB == 0) {
      return sign + (magnitude / MIB) + "Mi";
    }
    if (magnitude % KIB == 0) {
      return sign + (magnitude / KIB) + "Ki";
    }
    return sign + magnitude;
  }

  static String formatCpu(long millicores) {
    return millicores + "m";
  }

  static long parseMemory(String text) {
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
    return parsePositiveLong(trimmed, text);
  }

  static long parseCpu(String text) {
    String trimmed = text.strip();
    if (trimmed.endsWith("m")) {
      return parsePositiveLong(trimmed.substring(0, trimmed.length() - 1), text);
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
    return parsePositiveLong(numberPart, original) * multiplier;
  }

  private static long parsePositiveLong(String numberPart, String original) {
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
