package com.gimle.module.lifecycle;

/**
 * {@code FAILED} is a pragmatic addition beyond the spec's five named states: the terminal for a
 * resolve or {@code onStart} hook failure. There is no in-worker retry out of it -- but it is not a
 * dead end either: {@code WorkerRuntime} emits a {@code TransitionFailed} event on this transition
 * ({@code markFailedAndEmit}), which the worker's heartbeat reports as {@code alive=false} for this
 * instance. {@code HealthReconciler} (control plane) picks that up and, subject to its own
 * backoff-gated budget, reschedules the instance to a different node -- the cluster tier of
 * self-healing, not the in-worker restart tier {@code ACTIVE}'s liveness-probe failures use.
 *
 * <p>This is deliberate, not accidental: a hook failure most often means a bad artifact or a
 * misconfigured dependency, which an in-worker retry on the same node can't fix -- only a different
 * placement (or an operator re-resolving/uninstalling the deployment) can. Retrying locally would
 * just duplicate {@code HealthReconciler}'s job at a tier that has no way to act on the actual
 * cause.
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
