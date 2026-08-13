package com.gimle.module.lifecycle;

/** The outcome a {@link JobHooks#run} reports back to {@link ModuleController#complete}. */
public enum CompletionStatus {
  SUCCEEDED,
  FAILED
}
