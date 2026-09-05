package com.gimle.ivaldi.validate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One tier-2 validation outcome, in the same {@code {code, severity, message, file}} shape the
 * console's own tier-1 {@code Problem} carries -- {@code code} is the stable, grep-able part of the
 * contract (never renamed once shipped, even if {@code message}'s wording changes), and {@code
 * file} names which rendered file the finding is about.
 *
 * <p>A file alone is not enough to attribute a finding back to a node -- {@code topology.yaml}
 * holds every role, so a rule that fired on one of them pointed at all of them. {@code resource}
 * carries the thing itself where it is known, in the vocabulary the rendered document uses: a
 * topology section ({@code store}, {@code controlPlane}, {@code agents}), or a manifest's own
 * {@code Kind/name}. It is empty when nothing narrower than the file is honestly knowable.
 */
public record Finding(
    String code, Severity severity, String message, String file, Optional<String> resource) {

  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  public Finding(
      final String code, final Severity severity, final String message, final String file) {
    this(code, severity, message, file, Optional.empty());
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
    if (resource == null) {
      throw new IllegalArgumentException("resource must not be null; use Optional.empty()");
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

  /** The same finding, attributed to {@code resource} within its file. */
  public Finding about(String resource) {
    return new Finding(code, severity, message, file, Optional.of(resource));
  }

  public Map<String, Object> toJsonMap() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("code", code);
    json.put("severity", severity.name().toLowerCase(Locale.ROOT));
    json.put("message", message);
    json.put("file", file);
    resource.ifPresent(value -> json.put("resource", value));
    return json;
  }
}
