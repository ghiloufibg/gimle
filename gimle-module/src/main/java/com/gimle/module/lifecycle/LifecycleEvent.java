package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import com.gimle.module.resolve.ModuleWiring;
import java.time.Instant;

/** Immutable record of one lifecycle transition, for the platform's structured event log. */
public sealed interface LifecycleEvent {

  ModuleId id();

  Instant at();

  record Installed(ModuleId id, Instant at) implements LifecycleEvent {}

  record Resolved(ModuleId id, ModuleWiring wiring, Instant at) implements LifecycleEvent {}

  record Starting(ModuleId id, Instant at) implements LifecycleEvent {}

  record Active(ModuleId id, Instant at) implements LifecycleEvent {}

  record Stopping(ModuleId id, Instant deadline, Instant at) implements LifecycleEvent {}

  record Uninstalled(ModuleId id, Instant at) implements LifecycleEvent {}

  record TransitionFailed(
      ModuleId id, ModuleState from, ModuleState to, Throwable cause, Instant at)
      implements LifecycleEvent {}
}
