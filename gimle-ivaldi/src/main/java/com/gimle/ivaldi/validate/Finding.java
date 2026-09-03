package com.gimle.ivaldi.validate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One tier-2 validation outcome, in the same {@code {code, severity, message, file}} shape the
 * console's own tier-1 {@code Problem} carries -- {@code code} is the stable, grep-able part of the
 * contract (never renamed once shipped, even if {@code message}'s wording changes), and {@code
 * file} names which rendered file the finding is about, so the console can attribute it back to a
 * node the same way it already attributes a tier-1 finding.
 */
public record Finding(String code, Severity severity, String message, String file) {

  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  public Finding {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    if (file == null || file.isBlank()) {
      throw new IllegalArgumentException("file must not be blank");
    }
  }

  public static Finding error(String code, String message, String file) {
    return new Finding(code, Severity.ERROR, message, file);
  }

  public static Finding warning(String code, String message, String file) {
    return new Finding(code, Severity.WARNING, message, file);
  }

  public static Finding info(String code, String message, String file) {
    return new Finding(code, Severity.INFO, message, file);
  }

  public Map<String, Object> toJsonMap() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("code", code);
    json.put("severity", severity.name().toLowerCase(Locale.ROOT));
    json.put("message", message);
    json.put("file", file);
    return json;
  }
}
