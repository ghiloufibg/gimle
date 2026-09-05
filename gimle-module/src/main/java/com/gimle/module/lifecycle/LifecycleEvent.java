package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleInstanceId;
import com.gimle.module.resolve.ModuleWiring;
import java.time.Instant;

/** Immutable record of one lifecycle transition, for the platform's structured event log. */
public sealed interface LifecycleEvent {

  ModuleInstanceId id();

  Instant at();

  record Installed(ModuleInstanceId id, Instant at) implements LifecycleEvent {}

  record Resolved(ModuleInstanceId id, ModuleWiring wiring, Instant at) implements LifecycleEvent {}

  record Starting(ModuleInstanceId id, Instant at) implements LifecycleEvent {}

  record Active(ModuleInstanceId id, Instant at) implements LifecycleEvent {}

  record Stopping(ModuleInstanceId id, Instant deadline, Instant at) implements LifecycleEvent {}

  record Uninstalled(ModuleInstanceId id, Instant at) implements LifecycleEvent {}

  /**
   * The run-to-completion success path: a Job-kind module's {@code JobHooks.run(...)} returned
   * {@code CompletionStatus.SUCCEEDED}. The FAILED-status counterpart reuses {@link
   * TransitionFailed} rather than introducing a second terminal event type.
   */
  record Completed(ModuleInstanceId id, Instant at) implements LifecycleEvent {}

  record TransitionFailed(
      ModuleInstanceId id, ModuleState from, ModuleState to, Throwable cause, Instant at)
      implements LifecycleEvent {}
}
