package com.gimle.examples.greeting.operator;

import com.gimle.module.galdr.GaldrOperatorLoop;
import com.gimle.module.galdr.GaldrResource;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The controller half of the {@code custom.Greeting} walkthrough: on every level-triggered pass
 * over the full current set, says each Greeting's message its declared number of times and reports
 * {@code {timesSaid, observedGeneration}} back as that resource's status. Deliberately an ordinary
 * hosted module -- deployed by manifest, supervised, probed like anything else; the platform never
 * even knows it is an operator -- authorized purely by whatever RBAC binding its own {@code svc:}
 * workload principal carries.
 */
public final class GreetingOperatorHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GreetingOperatorHooks.class);

  static final AtomicBoolean running = new AtomicBoolean(false);

  private GaldrOperatorLoop loop;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    loop =
        GaldrOperatorLoop.start(
            ctx, "custom.Greeting", Duration.ofSeconds(5), GreetingOperatorHooks::reconcile);
    running.set(true);
    log.info("greeting-operator watching custom.Greeting");
  }

  private static void reconcile(java.util.List<GaldrResource> resources) {
    for (GaldrResource resource : resources) {
      // Per-resource try/catch: one Greeting an operator bug chokes on must never stop the rest
      // of the set from being said -- the next tick retries everything anyway.
      try {
        greet(resource);
      } catch (RuntimeException e) {
        log.warn("failed to reconcile Greeting {}: {}", resource.name(), e.getMessage());
      }
    }
  }

  private static void greet(GaldrResource resource) {
    int repeat = resource.spec().getInt("repeat");
    String message = resource.spec().getString("message");
    for (int i = 0; i < repeat; i++) {
      log.info("{} (greeting {} for {})", message, resource.name(), tenantOf(resource));
    }
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("timesSaid", repeat);
    status.put("observedGeneration", resource.generation());
    ModuleContext.RelayResult reported = resource.reportStatus(status);
    if (reported.status() != 200) {
      log.warn(
          "status report for Greeting {} answered {}: {}",
          resource.name(),
          reported.status(),
          reported.body());
    }
  }

  private static String tenantOf(GaldrResource resource) {
    return resource.tenantId().orElse("<untenanted>");
  }

  @Override
  public void onStop(ModuleContext ctx) {
    running.set(false);
    if (loop != null) {
      loop.close();
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
