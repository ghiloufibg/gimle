package com.gimle.holmgang;

/**
 * A validation-harness failure: a cluster that could not boot, a readiness condition that never
 * came true, an API call the scenario needed to succeed that didn't. Deliberately this module's own
 * type rather than one of {@code gimle-core}'s platform exception kinds -- those name failures of
 * the platform itself, while this names a failure of the harness around it.
 */
public class HolmgangException extends RuntimeException {

  public HolmgangException(final String message) {
    super(message);
  }

  public HolmgangException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
