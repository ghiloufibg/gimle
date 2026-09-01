package com.gimle.cli;

/**
 * The process exit code a failed {@code gimle} invocation ends with, so a shell script can branch
 * on <em>why</em> a command failed without parsing the human-readable stderr message.
 *
 * <p>The distinctions are the ones the control plane itself already draws in its HTTP status codes;
 * {@link CliException} carries one of these from wherever the failure is classified all the way out
 * to {@code GimleCli.run}'s return value.
 */
public enum CliExitCode {
  /** Anything the CLI cannot classify further, including client-side usage and argument errors. */
  GENERIC(1),
  /**
   * The request was rejected as malformed or semantically invalid, or a manifest failed to parse.
   */
  INVALID_INPUT(2),
  /** The addressed resource does not exist. */
  NOT_FOUND(3),
  /** The caller is unauthenticated, or authenticated but lacks the required permission. */
  FORBIDDEN(4),
  /** The request conflicts with the resource's current state. */
  CONFLICT(5),
  /** The server could not be reached, or answered in a way that says "try again". */
  UNAVAILABLE(6);

  private final int code;

  CliExitCode(final int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }
}
