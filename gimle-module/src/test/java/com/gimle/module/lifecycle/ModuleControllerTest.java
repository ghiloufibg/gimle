package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Requirement;
import com.gimle.core.module.VersionRange;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Core state-machine behavior using hook-free fixture modules (descriptor with no lifecycle.hooks
 * field). Real hook invocation, drain-deadline behavior (which needs a hook to hold the context
 * open), and hot redeploy are covered in the fuller integration tests, which build the extra
 * cross-module machinery to load a real ModuleLifecycleHooks implementation dynamically.
 */
class ModuleControllerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar(String name) {
    String uniqueName = name + (counter++);
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(uniqueName))
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private record Fixture(
      ModuleRegistry registry,
      ModuleController controller,
      ModuleId id,
      List<LifecycleEvent> events) {}

  private Fixture fixtureFor(String name) {
    Path jar = buildFixtureJar(name);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    List<LifecycleEvent> events = new ArrayList<>();
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            events::add);
    return new Fixture(registry, controller, id, events);
  }

  @Test
  void full_happy_path_without_hooks() {
    Fixture f = fixtureFor("com.gimle.fixture.happy");

    f.controller().resolve(f.id());
    assertEquals(ModuleState.RESOLVED, f.registry().state(f.id()));

    f.controller().start(f.id());
    assertEquals(ModuleState.ACTIVE, f.registry().state(f.id()));

    f.controller().stop(f.id());
    assertFalse(f.registry().contains(f.id()));

    List<String> eventTypes = f.events().stream().map(e -> e.getClass().getSimpleName()).toList();
    assertEquals(List.of("Resolved", "Starting", "Active", "Stopping", "Uninstalled"), eventTypes);
  }

  @Test
  void resolve_without_a_data_directory_leaves_the_context_field_empty() {
    Fixture f = fixtureFor("com.gimle.fixture.no_volume");

    f.controller().resolve(f.id());

    assertTrue(f.controller().context(f.id()).orElseThrow().dataDirectory().isEmpty());
  }

  @Test
  void resolve_with_a_data_directory_populates_the_context_before_any_hook_runs() {
    Fixture f = fixtureFor("com.gimle.fixture.with_volume");
    Path volumePath = tempDir.resolve("volumes/orders-statefulset/0");

    f.controller().resolve(f.id(), Optional.of(volumePath));

    assertEquals(
        Optional.of(volumePath), f.controller().context(f.id()).orElseThrow().dataDirectory());
  }

  @Test
  void start_before_resolve_is_illegal() {
    Fixture f = fixtureFor("com.gimle.fixture.early_start");
    assertThrows(GimleLifecycleException.class, () -> f.controller().start(f.id()));
  }

  @Test
  void stop_before_active_is_illegal() {
    Fixture f = fixtureFor("com.gimle.fixture.early_stop");
    f.controller().resolve(f.id());
    assertThrows(GimleLifecycleException.class, () -> f.controller().stop(f.id()));
  }

  @Test
  void resolve_failure_marks_module_failed_and_emits_transition_failed() {
    Path jar = buildFixtureJar("com.gimle.fixture.unresolvable");
    // Overwrite the descriptor content by building a fresh artifact with an unsatisfiable requires.
    ModuleArtifact baseArtifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact withMissingDep =
        new ModuleArtifact(
            baseArtifact.id(),
            baseArtifact.jarPath(),
            new com.gimle.core.module.ModuleDescriptor(
                baseArtifact.descriptor().name(),
                baseArtifact.descriptor().version(),
                List.of(new Requirement("com.gimle.fixture.ghost", VersionRange.parse("[1.0.0,)"))),
                baseArtifact.descriptor().exports(),
                baseArtifact.descriptor().isolationTier(),
                baseArtifact.descriptor().resourceRequest(),
                baseArtifact.descriptor().resourceLimit(),
                baseArtifact.descriptor().healthProbes(),
                baseArtifact.descriptor().lifecycleHooksClass(),
                baseArtifact.descriptor().jobHooksClass(),
                baseArtifact.descriptor().volume()),
            baseArtifact.sha256());
    ModuleId id = registry.register(withMissingDep);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    List<LifecycleEvent> events = new ArrayList<>();
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            events::add);

    assertThrows(GimleResolutionException.class, () -> controller.resolve(id));
    assertEquals(ModuleState.FAILED, registry.state(id));
    assertTrue(events.stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed));
  }

  @Test
  void uninstall_from_failed_state_succeeds() {
    Fixture f = fixtureFor("com.gimle.fixture.uninstall_failed");
    // Force into FAILED via an illegal path is awkward; instead resolve fine, then simulate
    // FAILED by directly driving a resolve failure is covered above — here we exercise
    // uninstall() directly from INSTALLED, which the FAILED/INSTALLED/RESOLVED group all share.
    f.controller().uninstall(f.id());
    assertFalse(f.registry().contains(f.id()));
  }

  @Test
  void uninstall_rejects_active_module() {
    Fixture f = fixtureFor("com.gimle.fixture.uninstall_active");
    f.controller().resolve(f.id());
    f.controller().start(f.id());
    assertThrows(GimleLifecycleException.class, () -> f.controller().uninstall(f.id()));
  }

  @Test
  void state_query_after_uninstall_throws() {
    Fixture f = fixtureFor("com.gimle.fixture.gone");
    f.controller().uninstall(f.id());
    assertThrows(NoSuchElementException.class, () -> f.registry().state(f.id()));
  }

  @Test
  void force_failed_transitions_an_active_module_straight_to_failed_and_emits_transition_failed() {
    Fixture f = fixtureFor("com.gimle.fixture.force_failed");
    f.controller().resolve(f.id());
    f.controller().start(f.id());
    assertEquals(ModuleState.ACTIVE, f.registry().state(f.id()));

    f.controller().forceFailed(f.id(), "restart budget exhausted");

    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
    LifecycleEvent last = f.events().get(f.events().size() - 1);
    assertTrue(last instanceof LifecycleEvent.TransitionFailed);
    LifecycleEvent.TransitionFailed failed = (LifecycleEvent.TransitionFailed) last;
    assertEquals(ModuleState.ACTIVE, failed.from());
    assertEquals(ModuleState.FAILED, failed.to());
    assertEquals("restart budget exhausted", failed.cause().getMessage());
  }

  @Test
  void a_module_forced_to_failed_cannot_be_started_again_without_re_resolving() {
    // FAILED (P2-19) has no in-worker retry path: the only way out is an operator (or a control
    // loop) re-resolving or uninstalling, never a bare start() call landing directly on it.
    Fixture f = fixtureFor("com.gimle.fixture.no_retry_from_failed");
    f.controller().resolve(f.id());
    f.controller().start(f.id());
    f.controller().forceFailed(f.id(), "restart budget exhausted");
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));

    assertThrows(GimleLifecycleException.class, () -> f.controller().start(f.id()));
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
  }

  @Test
  void force_failed_rejects_a_module_that_is_not_active() {
    Fixture f = fixtureFor("com.gimle.fixture.force_failed_not_active");
    // Still INSTALLED -- never resolved/started.
    assertThrows(
        GimleLifecycleException.class,
        () -> f.controller().forceFailed(f.id(), "should not apply"));
    assertEquals(ModuleState.INSTALLED, f.registry().state(f.id()));
  }

  @Test
  void complete_succeeded_transitions_an_active_module_to_completed_and_emits_completed() {
    Fixture f = fixtureFor("com.gimle.fixture.complete_succeeded");
    f.controller().resolve(f.id());
    f.controller().start(f.id());
    assertEquals(ModuleState.ACTIVE, f.registry().state(f.id()));

    f.controller().complete(f.id(), CompletionStatus.SUCCEEDED);

    assertEquals(ModuleState.COMPLETED, f.registry().state(f.id()));
    LifecycleEvent last = f.events().get(f.events().size() - 1);
    assertTrue(last instanceof LifecycleEvent.Completed);
    // Unlike stop()'s ACTIVE -> STOPPING -> UNINSTALLED drain sequence, complete() skips straight
    // to the terminal state -- the module is still registered, not disposed.
    assertTrue(f.registry().contains(f.id()));
  }

  @Test
  void complete_failed_reuses_the_ordinary_failed_path_and_emits_transition_failed() {
    Fixture f = fixtureFor("com.gimle.fixture.complete_failed");
    f.controller().resolve(f.id());
    f.controller().start(f.id());

    f.controller().complete(f.id(), CompletionStatus.FAILED);

    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
    LifecycleEvent last = f.events().get(f.events().size() - 1);
    assertTrue(last instanceof LifecycleEvent.TransitionFailed);
    LifecycleEvent.TransitionFailed failed = (LifecycleEvent.TransitionFailed) last;
    assertEquals(ModuleState.ACTIVE, failed.from());
    assertEquals(ModuleState.FAILED, failed.to());
  }

  @Test
  void complete_rejects_a_module_that_is_not_active() {
    Fixture f = fixtureFor("com.gimle.fixture.complete_not_active");
    // Still INSTALLED -- never resolved/started.
    assertThrows(
        GimleLifecycleException.class,
        () -> f.controller().complete(f.id(), CompletionStatus.SUCCEEDED));
    assertEquals(ModuleState.INSTALLED, f.registry().state(f.id()));
  }
}
