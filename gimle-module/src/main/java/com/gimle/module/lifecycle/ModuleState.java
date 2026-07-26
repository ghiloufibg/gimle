package com.gimle.module.lifecycle;

/**
 * {@code FAILED} is a pragmatic addition beyond the spec's five named states: the terminal for a
 * resolve or {@code onStart} hook failure. Phase 1 has no auto-retry/escalation out of it — a
 * failed module sits there until an operator (or, later, Phase 3's control loop) re-resolves or
 * uninstalls it.
 */
public enum ModuleState {
  INSTALLED,
  RESOLVED,
  STARTING,
  ACTIVE,
  STOPPING,
  UNINSTALLED,
  FAILED
}
