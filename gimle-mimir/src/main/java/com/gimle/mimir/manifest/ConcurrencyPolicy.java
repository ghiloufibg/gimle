package com.gimle.mimir.manifest;

/**
 * Governs what a {@link CronJobSpec} firing does when its previous firing's generated {@link
 * JobSpec} is still non-terminal -- mirrors Kubernetes CronJob's own three-value policy exactly, a
 * well-understood reference semantic rather than an invented one.
 */
public enum ConcurrencyPolicy {
  /** Let the new firing run alongside the still-running one. */
  ALLOW,
  /** Skip this firing entirely while the previous one is still running. */
  FORBID,
  /** Remove the still-running {@link JobSpec} first, then place the new firing. */
  REPLACE
}
