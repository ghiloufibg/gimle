package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.logging.InstanceSiftingFileAppender;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.worker.testsupport.PlatformJars;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * A module whose lifecycle hook fails to link -- the ordinary shape of a badly-packaged artifact,
 * where the class a hook first touches simply isn't in the jar -- fails with an {@code Error}, not
 * an exception. Nothing about that is exotic to a hosted module, but it used to leave the failure
 * recorded in no place an operator can read: the instance kept whatever state it had, no {@code
 * TransitionFailed} reached the timeline, no line named the cause, and the escape unwound the
 * worker's own control loop, taking every co-tenant module in that JVM down with it.
 */
class HookFailureVisibilityTest {

  private static final String MISSING_CLASS = "com.example.MissingDependency";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private record Fixture(
      ModuleRegistry registry,
      ModuleController controller,
      ModuleInstanceId id,
      List<LifecycleEvent> events) {}

  /** A module whose bundled {@code onStart} hook throws the linkage error a missing class does. */
  private Fixture moduleWhoseStartHookFailsToLink(String name) {
    Path jar =
        TestModuleBuilder.module(
                """
                module %s {
                  requires static com.gimle.module;
                  exports %s;
                }
                """
                    .formatted(name, name))
            .withClass(
                name + ".UnlinkableHooks",
                """
                package %s;
                import com.gimle.module.lifecycle.ModuleContext;
                import com.gimle.module.lifecycle.ModuleLifecycleHooks;
                public final class UnlinkableHooks implements ModuleLifecycleHooks {
                  public void onInstall(ModuleContext ctx) {}
                  public void onStart(ModuleContext ctx) {
                    throw new NoClassDefFoundError("%s");
                  }
                  public void onStop(ModuleContext ctx) {}
                  public void onUninstall(ModuleContext ctx) {}
                }
                """
                    .formatted(name, MISSING_CLASS))
            .withDescriptor(
                """
                name: %s
                version: 1.0.0
                isolation:
                  tier: TIER_1
                resources:
                  request:
                    memory: 16Mi
                    cpu: 10m
                  limit:
                    memory: 32Mi
                    cpu: 50m
                lifecycle:
                  hooks: %s.UnlinkableHooks
                """
                    .formatted(name, name))
            .dependsOn(PlatformJars.onTestClasspath().toArray(Path[]::new))
            .build(tempDir, "unlinkable.jar");

    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            PlatformLayer.bootOnly().layer(),
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            events::add);
    ModuleInstanceId id = controller.install(artifact, "");
    controller.resolve(id);
    return new Fixture(registry, controller, id, events);
  }

  private static LifecycleEvent.TransitionFailed onlyTransitionFailure(
      List<LifecycleEvent> events) {
    List<LifecycleEvent.TransitionFailed> failures =
        events.stream()
            .filter(LifecycleEvent.TransitionFailed.class::isInstance)
            .map(LifecycleEvent.TransitionFailed.class::cast)
            .toList();
    assertEquals(1, failures.size(), "expected exactly one recorded failure in " + events);
    return failures.get(0);
  }

  @Test
  void a_start_hook_that_fails_to_link_is_recorded_instead_of_escaping_uncaught() {
    Fixture f = moduleWhoseStartHookFailsToLink("com.gimle.fixture.unlinkable");

    assertThrows(GimleLifecycleException.class, () -> f.controller().start(f.id()));

    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
    LifecycleEvent.TransitionFailed failed = onlyTransitionFailure(f.events());
    assertEquals(ModuleState.STARTING, failed.to());
    String detail = WorkerMain.transitionFailureDetail(failed.cause());
    assertTrue(detail.contains("onStart"), "must name the hook that failed: " + detail);
    assertTrue(detail.contains("NoClassDefFoundError"), "must name the real cause: " + detail);
    assertTrue(detail.contains(MISSING_CLASS), "must name what could not be linked: " + detail);
  }

  /**
   * Attaches and detaches the sifting appender around this one instance, the way {@code
   * AgentLogServerTest} does: it listens on the shared root logger, so leaving it attached would
   * have it sift (and hold a file open for) every other test's lines too.
   */
  @Test
  void a_start_hook_that_fails_to_link_reaches_the_instances_own_log_with_its_stack_trace()
      throws Exception {
    Fixture f = moduleWhoseStartHookFailsToLink("com.gimle.fixture.unlinkable.logged");
    Path instancesDir = tempDir.resolve("instances");
    Logger root =
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    InstanceSiftingFileAppender appender =
        (InstanceSiftingFileAppender) GimleLogging.attachInstanceSiftingAppender(instancesDir);

    String contents;
    try {
      Map<String, String> tags =
          InstanceMdcContext.tagsFor(
              "greeter", 0, f.id().name(), f.id().version().toString(), null);
      assertThrows(
          GimleLifecycleException.class,
          () ->
              InstanceMdcContext.runTagged(
                  tags,
                  () -> {
                    f.controller().start(f.id());
                    return null;
                  }));
      contents = Files.readString(instancesDir.resolve("greeter-0.log"));
    } finally {
      appender.closeInstance("greeter", 0);
      root.detachAppender(appender);
      appender.stop();
    }

    assertTrue(
        contents.contains("NoClassDefFoundError"),
        "the instance's own log must name the cause: " + contents);
    assertTrue(
        contents.contains(MISSING_CLASS),
        "the instance's own log must name what could not be linked: " + contents);
    assertTrue(
        contents.contains("com.gimle.fixture.unlinkable.logged.UnlinkableHooks.onStart"),
        "the instance's own log must carry the hook's own stack frame: " + contents);
  }
}
