package com.gimle.core.module;

/**
 * What happens to a volume's on-disk data when its owning instance is permanently removed (a real
 * scale-down, or the whole workload spec deleted -- never an ordinary reschedule or rolling-update
 * replacement, which keep the data by design). {@link #RETAIN} is the default: the directory is
 * left in place for an operator to inspect or explicitly destroy, so one mistaken scale-down or
 * spec delete never silently destroys data. {@link #DELETE} opts into immediate recursive removal
 * for data that is genuinely disposable (a cache, a scratch spool).
 */
public enum ReclaimPolicy {
  RETAIN,
  DELETE
}
