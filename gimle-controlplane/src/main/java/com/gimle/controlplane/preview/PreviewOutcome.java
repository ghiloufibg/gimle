package com.gimle.controlplane.preview;

/** How one {@link PreviewCheck} of a dry-run came out. */
public enum PreviewOutcome {
  PASSED,
  FAILED,
  /**
   * Not evaluated, and deliberately so -- either an earlier check already decided the submission
   * would be rejected (so nothing downstream of it would have run for real either), or the check
   * has nothing to say about this workload kind. Never means "could not tell".
   */
  SKIPPED
}
