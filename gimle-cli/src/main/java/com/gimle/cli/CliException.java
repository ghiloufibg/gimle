package com.gimle.cli;

/**
 * A user-facing CLI failure: unreachable server, a rejected request, or bad command-line usage.
 * {@code GimleCli.main} catches this at the top level and prints its message to stderr with a
 * non-zero exit code, rather than a raw stack trace -- a human at a terminal, not a caller
 * expecting a typed domain exception the way {@code gimle-core}'s {@code Gimle*Exception} family is
 * consumed elsewhere in this codebase.
 *
 * <p>Every instance also carries a {@link CliExitCode}, so the reason a call failed survives from
 * wherever it was classified out to the process's own exit status. The two plain constructors mean
 * {@link CliExitCode#GENERIC}: a client-side usage or argument mistake, which the CLI reports in
 * prose but does not attempt to categorize further. The named factories below are for failures the
 * CLI genuinely can categorize -- almost always a control-plane response whose HTTP status already
 * drew the distinction.
 */
public final class CliException extends RuntimeException {

  private final CliExitCode exitCode;

  public CliException(final String message) {
    this(message, CliExitCode.GENERIC);
  }

  public CliException(final String message, final Throwable cause) {
    this(message, CliExitCode.GENERIC, cause);
  }

  public CliException(final String message, final CliExitCode exitCode) {
    super(message);
    this.exitCode = exitCode;
  }

  public CliException(final String message, final CliExitCode exitCode, final Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
  }

  public CliExitCode exitCode() {
    return exitCode;
  }

  public static CliException invalidInput(final String message) {
    return new CliException(message, CliExitCode.INVALID_INPUT);
  }

  public static CliException notFound(final String message) {
    return new CliException(message, CliExitCode.NOT_FOUND);
  }

  public static CliException forbidden(final String message) {
    return new CliException(message, CliExitCode.FORBIDDEN);
  }

  public static CliException conflict(final String message) {
    return new CliException(message, CliExitCode.CONFLICT);
  }

  public static CliException unavailable(final String message) {
    return new CliException(message, CliExitCode.UNAVAILABLE);
  }

  public static CliException unavailable(final String message, final Throwable cause) {
    return new CliException(message, CliExitCode.UNAVAILABLE, cause);
  }
}
