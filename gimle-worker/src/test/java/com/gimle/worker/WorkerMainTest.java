package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.resolve.ModuleRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Covers what an instance's own durable timeline says about a failure. {@code
 * WorkerMain#instanceEventFor}'s {@code TransitionFailed} case used to report only {@code
 * GimleLifecycleException.hookFailed}'s own generic wrapper message ("lifecycle hook 'onStart'
 * threw an exception") as its {@code detail} -- the module's own real, well-typed exception (naming
 * exactly which config key is missing, say) was swallowed entirely, reaching neither {@code gimle
 * logs}, {@code gimle events}, nor any API surface -- and its own message read "transition ACTIVE
 * -> FAILED failed" for an instance that had reached FAILED exactly as intended. A liveness-driven
 * restart, meanwhile, wrote no entry naming itself at all.
 */
class WorkerMainTest {

  private static final ModuleInstanceId ID =
      ModuleInstanceId.unattached(
          new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));
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

  @Test
  void transition_failed_message_names_the_transition_rather_than_reading_failed_to_fail() {
    LifecycleEvent.TransitionFailed event =
        new LifecycleEvent.TransitionFailed(
            ID,
            ModuleState.ACTIVE,
            ModuleState.FAILED,
            new IllegalStateException("restart budget exhausted"),
            Instant.now());

    InstanceEvent instanceEvent = WorkerMain.instanceEventFor(event, IDENTITY);

    assertEquals("could not transition from ACTIVE to FAILED", instanceEvent.message());
  }

  @Test
  void a_liveness_driven_restart_is_recorded_as_its_own_kind_naming_the_failure_count() {
    InstanceEvent event = WorkerMain.livenessFailureEventFor(IDENTITY, 3);

    assertEquals(InstanceEventKind.LIVENESS_FAILED, event.kind());
    assertEquals("greeter", event.deploymentName());
    assertEquals(0, event.instanceIndex());
    assertEquals("liveness probe failed 3 times in a row; restarting module", event.message());
  }

  @Test
  void a_single_liveness_failure_is_not_pluralized() {
    assertEquals(
        "liveness probe failed 1 time in a row; restarting module",
        WorkerMain.livenessFailureEventFor(IDENTITY, 1).message());
  }

  // ---- surge-promotion renames (ControlMessage.RenameInstance) ----

  /**
   * A rename retargets a running instance without any lifecycle transition, so nothing reaches the
   * lifecycle sink and both indexes involved need their timeline entries minted by hand.
   */
  private static ModuleRegistry registryHosting(ModuleInstanceId id, ModuleState state) {
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            id.name(),
            id.version(),
            List.of(),
            List.of(),
            IsolationTier.TIER_1,
            new ResourceSpec("16Mi", "10m"),
            new ResourceSpec("32Mi", "50m"),
            HealthProbes.NONE,
            Optional.empty(),
            Optional.empty(),
            Map.of());
    ModuleRegistry registry = new ModuleRegistry();
    registry.register(
        new ModuleArtifact(descriptor.id(), Path.of("greeter.jar"), descriptor, "0".repeat(64)),
        id.instanceKey());
    switch (state) {
      case ACTIVE -> registry.markActive(id);
      case FAILED -> registry.markFailed(id);
      default -> {}
    }
    return registry;
  }

  private static ControlMessage.RenameInstance renameTo(ModuleInstanceId id, int instanceIndex) {
    return new ControlMessage.RenameInstance("c1", id, "greeter", instanceIndex);
  }

  @Test
  void promoting_a_surge_instance_carries_the_reused_index_timeline_past_its_teardown() {
    ModuleInstanceId id =
        ModuleInstanceId.of(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")), "", "greeter", 3);
    ModuleRegistry registry = registryHosting(id, ModuleState.ACTIVE);
    InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
    identities.register(id, new InstanceIdentity("greeter", 3, Optional.empty()));

    List<InstanceEvent> events =
        WorkerMain.applyRename(renameTo(id, 0), registry, identities, Optional.empty());

    InstanceEvent adopted =
        events.stream()
            .filter(event -> event.instanceIndex() == 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no entry for the reused index in " + events));
    assertEquals(
        InstanceEventKind.ACTIVE,
        adopted.kind(),
        "the reused index's timeline must reflect the running instance now holding it");
    assertTrue(adopted.message().contains("index 3"), "must name where it came from: " + adopted);
  }

  @Test
  void promoting_a_surge_instance_closes_off_the_index_it_gave_up() {
    ModuleInstanceId id =
        ModuleInstanceId.of(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")), "", "greeter", 3);
    ModuleRegistry registry = registryHosting(id, ModuleState.ACTIVE);
    InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
    identities.register(id, new InstanceIdentity("greeter", 3, Optional.empty()));

    List<InstanceEvent> events =
        WorkerMain.applyRename(renameTo(id, 0), registry, identities, Optional.empty());

    InstanceEvent retired =
        events.stream()
            .filter(event -> event.instanceIndex() == 3)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no entry for the retired index in " + events));
    assertEquals(InstanceEventKind.UNINSTALLED, retired.kind());
    assertTrue(retired.message().contains("index 0"), "must name where it went: " + retired);
    assertEquals(
        Optional.of(new InstanceIdentity("greeter", 0, Optional.empty())), identities.lookup(id));
  }

  @Test
  void a_promotion_of_an_instance_that_has_since_failed_is_not_recorded_as_a_healthy_one() {
    ModuleInstanceId id =
        ModuleInstanceId.of(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")), "", "greeter", 3);
    ModuleRegistry registry = registryHosting(id, ModuleState.FAILED);
    InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
    identities.register(id, new InstanceIdentity("greeter", 3, Optional.empty()));

    List<InstanceEvent> events =
        WorkerMain.applyRename(renameTo(id, 0), registry, identities, Optional.empty());

    assertEquals(
        InstanceEventKind.TRANSITION_FAILED,
        events.stream()
            .filter(event -> event.instanceIndex() == 0)
            .findFirst()
            .orElseThrow()
            .kind());
  }

  @Test
  void a_rename_repeating_the_identity_an_instance_already_holds_records_nothing() {
    ModuleInstanceId id =
        ModuleInstanceId.of(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")), "", "greeter", 0);
    ModuleRegistry registry = registryHosting(id, ModuleState.ACTIVE);
    InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
    identities.register(id, new InstanceIdentity("greeter", 0, Optional.empty()));

    assertEquals(
        List.of(), WorkerMain.applyRename(renameTo(id, 0), registry, identities, Optional.empty()));
  }

  @Test
  void a_rename_of_a_module_this_worker_no_longer_hosts_records_nothing() {
    ModuleInstanceId id =
        ModuleInstanceId.of(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")), "", "greeter", 3);
    InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
    identities.register(id, new InstanceIdentity("greeter", 3, Optional.empty()));

    assertEquals(
        List.of(),
        WorkerMain.applyRename(
            renameTo(id, 0), new ModuleRegistry(), identities, Optional.empty()));
  }
}
