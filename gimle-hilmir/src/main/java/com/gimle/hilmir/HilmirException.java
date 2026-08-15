package com.gimle.hilmir;

/**
 * A user-facing hilmir failure: bad command-line usage, a missing/unreadable file, or a request
 * hilmir can't currently satisfy. {@code HilmirMain.run} catches this at the top level and prints
 * its message to stderr with a non-zero exit code, rather than a raw stack trace -- a human at a
 * terminal, not a caller expecting a typed domain exception the way {@code gimle-core}'s {@code
 * Gimle*Exception} family is consumed elsewhere in this codebase. Mirrors {@code gimle-cli}'s own
 * {@code CliException} deliberately: small enough to duplicate rather than add a dependency on
 * {@code gimle-cli} for one exception class.
 */
public final class HilmirException extends RuntimeException {

  public HilmirException(final String message) {
    super(message);
  }

  public HilmirException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
