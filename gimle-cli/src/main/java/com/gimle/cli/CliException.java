package com.gimle.cli;

/**
 * A user-facing CLI failure: unreachable server, a rejected request, or bad command-line usage.
 * {@code GimleCli.main} catches this at the top level and prints its message to stderr with a
 * non-zero exit code, rather than a raw stack trace -- a human at a terminal, not a caller
 * expecting a typed domain exception the way {@code gimle-core}'s {@code Gimle*Exception} family is
 * consumed elsewhere in this codebase.
 */
public final class CliException extends RuntimeException {

  public CliException(String message) {
    super(message);
  }

  public CliException(String message, Throwable cause) {
    super(message, cause);
  }
}
