package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retraction of deleted config/secret keys from a running instance, and the change-listener a
 * hosted module registers to react to one. Uses the same hook-free fixture-module shape {@code
 * ModuleControllerTest} does, since what matters here is the controller's config bookkeeping and
 * the contexts it hands out, not any hook behavior.
 */
class ModuleConfigRetractionTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private record Fixture(ModuleController controller, ModuleId id) {}

  private Fixture fixture() {
    String uniqueName = "com.gimle.fixture.config" + (counter++);
    Path jar =
        TestModuleBuilder.module(
                """
                module %s {
                }
                """
                    .formatted(uniqueName))
            .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
            .build(tempDir, uniqueName + ".jar");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleController controller =
        new ModuleController(
            registry,
            new ModuleResolver(registry),
            PlatformLayer.bootOnly().layer(),
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            event -> {});
    controller.resolve(id);
    return new Fixture(controller, id);
  }

  private static ModuleContext contextOf(Fixture f) {
    return f.controller().context(f.id()).orElseThrow();
  }

  @Test
  void a_key_the_retained_set_no_longer_names_stops_being_readable() {
    Fixture f = fixture();
    f.controller().deliverConfig("db.url", "jdbc:h2:mem:");
    f.controller().deliverConfig("db.password", "hunter2");

    f.controller().retainConfigKeys(List.of("db.url"));

    ModuleContext ctx = contextOf(f);
    assertEquals(Optional.of("jdbc:h2:mem:"), ctx.config("db.url"));
    assertEquals(Optional.empty(), ctx.config("db.password"));
    assertEquals(Set.of("db.url"), ctx.configKeys());
  }

  @Test
  void an_empty_retained_set_revokes_everything() {
    Fixture f = fixture();
    f.controller().deliverConfig("a", "1");
    f.controller().deliverConfig("b", "2");

    f.controller().retainConfigKeys(List.of());

    assertEquals(Set.of(), contextOf(f).configKeys());
  }

  @Test
  void retention_converges_on_the_asserted_set_from_an_arbitrary_starting_state() {
    Fixture f = fixture();
    // A worker that has accumulated a mixture of keys over a history nobody replayed to it -- some
    // still real upstream, some long since deleted, one delivered twice with different values.
    f.controller().deliverConfig("stale-1", "old");
    f.controller().deliverConfig("stale-2", "older");
    f.controller().deliverConfig("live", "v1");
    f.controller().deliverConfig("live", "v2");
    f.controller().deliverConfig("also-live", "x");

    // One assertion of the current truth, with no knowledge of any of the above.
    f.controller().retainConfigKeys(List.of("live", "also-live"));

    ModuleContext ctx = contextOf(f);
    assertEquals(Set.of("live", "also-live"), ctx.configKeys());
    assertEquals(Optional.of("v2"), ctx.config("live"));

    // Re-asserting the same set changes nothing further -- convergence is stable, not oscillating.
    f.controller().retainConfigKeys(List.of("live", "also-live"));
    assertEquals(Set.of("live", "also-live"), ctx.configKeys());
  }

  @Test
  void a_listener_sees_a_delivery_a_rotation_and_a_retraction() {
    Fixture f = fixture();
    List<ModuleContext.ConfigChange> seen = new ArrayList<>();
    contextOf(f).onConfigChange(seen::add);

    f.controller().deliverConfig("api.key", "v1");
    f.controller().deliverConfig("api.key", "v2");
    f.controller().retainConfigKeys(List.of());

    assertEquals(
        List.of(
            new ModuleContext.ConfigChange("api.key", Optional.of("v1")),
            new ModuleContext.ConfigChange("api.key", Optional.of("v2")),
            new ModuleContext.ConfigChange("api.key", Optional.empty())),
        seen);
    assertTrue(seen.get(2).retracted());
  }

  @Test
  void a_re_delivered_unchanged_value_does_not_wake_a_listener() {
    Fixture f = fixture();
    List<ModuleContext.ConfigChange> seen = new ArrayList<>();
    contextOf(f).onConfigChange(seen::add);

    f.controller().deliverConfig("stable", "same");
    f.controller().deliverConfig("stable", "same");
    f.controller().retainConfigKeys(List.of("stable"));

    assertEquals(1, seen.size());
  }

  @Test
  void a_listener_reading_config_from_its_own_callback_sees_the_already_applied_state() {
    Fixture f = fixture();
    ModuleContext ctx = contextOf(f);
    List<Optional<String>> observed = new ArrayList<>();
    ctx.onConfigChange(change -> observed.add(ctx.config(change.key())));

    f.controller().deliverConfig("k", "v");
    f.controller().retainConfigKeys(List.of());

    assertEquals(List.of(Optional.of("v"), Optional.empty()), observed);
  }

  @Test
  void a_listener_that_throws_does_not_stop_the_others_or_the_change_itself() {
    Fixture f = fixture();
    ModuleContext ctx = contextOf(f);
    List<String> survivorSaw = new ArrayList<>();
    ctx.onConfigChange(
        change -> {
          throw new IllegalStateException("module listener blew up");
        });
    ctx.onConfigChange(change -> survivorSaw.add(change.key()));

    f.controller().deliverConfig("k", "v");

    assertEquals(List.of("k"), survivorSaw);
    assertEquals(Optional.of("v"), ctx.config("k"));
  }

  @Test
  void a_cancelled_subscription_stops_receiving_changes() {
    Fixture f = fixture();
    List<String> seen = new ArrayList<>();
    ModuleContext.ConfigSubscription subscription =
        contextOf(f).onConfigChange(change -> seen.add(change.key()));

    f.controller().deliverConfig("before", "v");
    subscription.cancel();
    f.controller().deliverConfig("after", "v");

    assertEquals(List.of("before"), seen);
  }

  @Test
  void an_uninstalled_instance_s_listener_is_never_called_again() {
    Fixture f = fixture();
    List<String> seen = new ArrayList<>();
    contextOf(f).onConfigChange(change -> seen.add(change.key()));

    // stop() drains and then uninstalls in one step here, dropping the context this listener
    // lives on -- which is exactly what keeps a disposed module's listener (and its classloader)
    // from being retained by the worker-wide config bookkeeping.
    f.controller().start(f.id());
    f.controller().stop(f.id());
    f.controller().deliverConfig("after-uninstall", "v");

    assertEquals(List.of(), seen);
  }

  @Test
  void registering_a_null_listener_is_rejected_rather_than_failing_on_the_next_delivery() {
    Fixture f = fixture();
    ModuleContext ctx = contextOf(f);

    assertThrows(IllegalArgumentException.class, () -> ctx.onConfigChange(null));
  }
}
