package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;

/**
 * Turns a {@link UtgardExecResult} into a forensic-quality description or a hard failure -- the
 * command, its exit code, and the whole of its captured stdout/stderr, matching this module's own
 * "a failed condition throws the investigation" ethos elsewhere (see {@code ForensicReport} in
 * {@code com.gimle.testkit.heimdall}), scaled down to one container command's own output instead of
 * a whole cluster view.
 */
final class UtgardExec {

  private UtgardExec() {}

  /** Returns {@code result} unchanged if it succeeded; throws with the full transcript if not. */
  static UtgardExecResult requireSuccess(final UtgardExecResult result, final String description) {
    if (!result.succeeded()) {
      throw new HolmgangException(description + " failed:\n" + describe(result, description));
    }
    return result;
  }

  static String describe(final UtgardExecResult result, final String description) {
    return description
        + " (exit "
        + result.exitCode()
        + "): "
        + String.join(" ", result.command())
        + "\n--- stdout ---\n"
        + result.stdout()
        + "\n--- stderr ---\n"
        + result.stderr();
  }
}
