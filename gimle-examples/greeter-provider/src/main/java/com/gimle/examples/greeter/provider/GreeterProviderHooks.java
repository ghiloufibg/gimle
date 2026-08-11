package com.gimle.examples.greeter.provider;

import com.gimle.examples.greeter.Greeter;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers a real {@link Greeter} on the fabric when this instance starts. {@link
 * GreeterReadinessProbe} shares {@link #ready} with this class: the instance isn't ready for
 * traffic until the service is actually registered and reachable.
 */
public final class GreeterProviderHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GreeterProviderHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(Greeter.class, name -> "Hello, " + name + "! (from provider)");
    ready.set(true);
    log.info("greeter-provider registered its Greeter service on the fabric");
    // Exercises the real config/secrets delivery path end to end (design doc §11 Phase C): the
    // agent fetches this tenant's secrets straight from Fafnir and hands them down alongside plain
    // config, so this is a real write-via-API -> fetch-via-agent -> observed-inside-a-deployed-
    // module round trip, not a unit-level check. Logged, not asserted here -- gimle-smoke-tests'
    // GreeterClusterTopologyIT reads it back out of this instance's own application log.
    Optional<String> secret = ctx.config("some-secret-key");
    log.info("greeter-provider read some-secret-key: {}", secret.orElse("<absent>"));
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
