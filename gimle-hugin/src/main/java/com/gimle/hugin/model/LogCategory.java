package com.gimle.hugin.model;

/**
 * The log categories an instance's own log route serves. {@code c} cycles between them, the same
 * two values {@code gimle logs --category} accepts for an instance target.
 */
public enum LogCategory {

  /** The instance's own logging: what the hosted module itself wrote. */
  APPLICATION,

  /** The platform's logging about the instance: lifecycle, probes, supervision. */
  PLATFORM;

  public LogCategory next() {
    return this == APPLICATION ? PLATFORM : APPLICATION;
  }
}
