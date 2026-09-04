package com.gimle.agent.testsupport;

import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;

/**
 * The simplest possible {@code lifecycle.jobHooks} fixture: returns {@link
 * CompletionStatus#SUCCEEDED} immediately, no shared static state to steer or observe -- unlike
 * {@code gimle-worker}'s own {@code RecordingJobHooks}, which isn't visible to a real {@code
 * WorkerMain} subprocess spawned from this module's own tests ({@code gimle-agent} depends on
 * {@code gimle-worker} for its main classes only, not its test-jar). Instantiated by reflection
 * inside the fixture module's own dynamically-created {@code ModuleLayer}, straight off the
 * subprocess's inherited {@code java.class.path} the same way {@code RecordingJobHooks} is for an
 * in-JVM test.
 */
public final class InstantSucceedingJobHooks implements JobHooks {

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    return CompletionStatus.SUCCEEDED;
  }
}
