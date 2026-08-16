package com.gimle.testkit.heimdall;

/**
 * A condition that failed -- timed out, or failed fast because a cluster process died unexpectedly.
 * The message carries the whole forensic report (last cluster view, process status, recent events,
 * log locations), so the assertion failure alone is enough to start an investigation without
 * re-running anything. An {@link AssertionError} so every test framework reports it as a failure,
 * not an infrastructure error.
 */
public class HeimdallConditionError extends AssertionError {

  HeimdallConditionError(final String report) {
    super(report);
  }
}
