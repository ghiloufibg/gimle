package com.gimle.hugin.model;

import java.util.Locale;

/**
 * One thing the scan found wrong, said as a subject and a sentence about it.
 *
 * <p>{@code detail} is written to be read on its own, without the heading above it: a finding
 * scrolled away from its group still has to say what is wrong. {@code severity} orders the list and
 * decides its colour; the model chooses the severity because how bad a thing is belongs with what
 * the thing is, but it never chooses the colour, which is the screen's own business -- the same
 * division every other row type here keeps.
 */
public record ScanFinding(
    ScanFinding.Severity severity, String group, String subject, String detail) {

  /** How much of a problem a finding is, in the order an operator would work through them. */
  public enum Severity {
    /** Something is not running that was asked to run, or cannot be reached at all. */
    ERROR,
    /** Something is degraded, at a limit, or heading for one. */
    WARNING,
    /** Worth knowing and not wrong: a deliberate state that looks like a fault from a distance. */
    NOTE
  }

  public ScanFinding {
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
    if (group == null || group.isBlank()) {
      throw new IllegalArgumentException("group must not be blank");
    }
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    if (detail == null || detail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
  }

  /** The text a filter is matched against: everything the row shows. */
  public String searchText() {
    return (group + " " + subject + " " + detail + " " + severity).toLowerCase(Locale.ROOT);
  }
}
