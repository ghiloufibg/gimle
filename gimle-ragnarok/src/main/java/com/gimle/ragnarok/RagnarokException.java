package com.gimle.ragnarok;

/**
 * A Ragnarök failure: a chaos plan that fails validation, a stress workload document that won't
 * parse, a recovery gate the caller asked to be told about rather than have thrown as an assertion
 * error. Deliberately this module's own type rather than one of {@code gimle-core}'s platform
 * exception kinds -- those name failures of the platform itself, while this names a failure of the
 * tool driving faults and load against it.
 */
public class RagnarokException extends RuntimeException {

  public RagnarokException(final String message) {
    super(message);
  }

  public RagnarokException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
