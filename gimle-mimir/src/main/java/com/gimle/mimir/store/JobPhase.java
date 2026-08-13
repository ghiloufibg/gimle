package com.gimle.mimir.store;

/**
 * A {@link com.gimle.mimir.manifest.JobSpec}'s own terminal-tracking state, persisted per job name
 * -- separate from any individual {@link JobRun}'s outcome, since a {@code JobSpec} outlives every
 * attempt it makes. {@code RUNNING} covers both "no attempt placed yet" and "an attempt is
 * currently in flight"; {@code SUCCEEDED}/{@code FAILED} are terminal -- once reached, {@code
 * JobReconciler} stops placing further attempts for that job.
 */
public enum JobPhase {
  RUNNING,
  SUCCEEDED,
  FAILED
}
