package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * JOURNEY-2a regression coverage: {@code WorkerMain#instanceEventFor}'s {@code TransitionFailed}
 * case used to report only {@code GimleLifecycleException.hookFailed}'s own generic wrapper message
 * ("lifecycle hook 'onStart' threw an exception") as its {@code detail} -- the module's own real,
 * well-typed exception (naming exactly which config key is missing, say) was swallowed entirely,
 * reaching neither {@code gimle logs}, {@code gimle events}, nor any API surface.
 */
class WorkerMainTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));
  private static final InstanceIdentity IDENTITY =
      new InstanceIdentity("greeter", 0, Optional.empty());

  @Test
  void transition_failed_detail_names_the_real_cause_behind_a_hook_wrapper() {
    RuntimeException realCause =
        new IllegalStateException("missing required config key 'greeting.prefix'");
    GimleLifecycleException wrapper = GimleLifecycleException.hookFailed(ID, "onStart", realCause);

    String detail = WorkerMain.transitionFailureDetail(wrapper);

    assertTrue(
        detail.contains("lifecycle hook 'onStart' threw an exception"),
        "must still name which hook failed: " + detail);
    assertTrue(
        detail.contains("IllegalStateException"), "must name the real cause's class: " + detail);
    assertTrue(
        detail.contains("missing required config key 'greeting.prefix'"),
        "must include the real cause's own message: " + detail);
  }

  @Test
  void transition_failed_event_detail_carries_the_unwrapped_cause() {
    RuntimeException realCause = new IllegalStateException("missing config key 'db.url'");
    GimleLifecycleException wrapper = GimleLifecycleException.hookFailed(ID, "onStart", realCause);
    LifecycleEvent.TransitionFailed event =
        new LifecycleEvent.TransitionFailed(
            ID, ModuleState.STARTING, ModuleState.FAILED, wrapper, Instant.now());

    InstanceEvent instanceEvent = WorkerMain.instanceEventFor(event, IDENTITY);

    assertTrue(instanceEvent.causeSummary().isPresent());
    assertTrue(instanceEvent.causeSummary().get().contains("missing config key 'db.url'"));
  }

  @Test
  void transition_failed_detail_falls_back_to_class_and_message_for_a_non_wrapper_cause() {
    IllegalStateException notAWrapper = new IllegalStateException("cannot transition A to B");

    String detail = WorkerMain.transitionFailureDetail(notAWrapper);

    assertEquals("java.lang.IllegalStateException: cannot transition A to B", detail);
  }

  @Test
  void transition_failed_detail_falls_back_when_the_wrapper_has_no_cause_of_its_own() {
    GimleLifecycleException illegalTransition =
        GimleLifecycleException.illegalTransition(ID, "STARTING", "ACTIVE");

    String detail = WorkerMain.transitionFailureDetail(illegalTransition);

    assertEquals(
        illegalTransition.getClass().getName() + ": " + illegalTransition.getMessage(), detail);
  }
}
