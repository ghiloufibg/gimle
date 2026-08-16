package com.gimle.testkit;

/**
 * A real-cluster test-harness failure: a lease that could not be reserved, a condition that failed
 * outright rather than merely timing out, a watcher interrupted mid-wait. Deliberately this
 * module's own type rather than one of {@code gimle-core}'s platform exception kinds -- those name
 * failures of the platform itself, while this names a failure of the harness around it.
 */
public class TestkitException extends RuntimeException {

  public TestkitException(final String message) {
    super(message);
  }

  public TestkitException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
