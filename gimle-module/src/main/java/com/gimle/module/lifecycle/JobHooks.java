package com.gimle.module.lifecycle;

/**
 * The run-to-completion counterpart to {@link ModuleLifecycleHooks} (priority-3 design doc §3a): a
 * Job-kind module's own unit of work, run to completion exactly once rather than living on until
 * told to stop. Declared in {@code gimle-module.yaml} as {@code lifecycle.jobHooks}, a sibling
 * field to {@code lifecycle.hooks}, and instantiated the same reflective,
 * one-interface-a-hook-class can-also-implement way {@link
 * com.gimle.module.probe.LivenessProbe}/{@link com.gimle.module.probe.ReadinessProbe} already are
 * -- not a parallel mechanism.
 *
 * <p>A Job-kind module has no liveness/readiness semantics worth enforcing (it's never "ready to
 * serve"), so {@code health:} is simply omitted from its descriptor; a worker drives {@link #run}
 * on its own virtual thread once the module reaches {@code ACTIVE}, then feeds the returned {@link
 * CompletionStatus} into {@link ModuleController#complete}.
 */
public interface JobHooks {

  /** Blocking; runs to completion on its own virtual thread once the module reaches ACTIVE. */
  CompletionStatus run(ModuleContext ctx);
}
