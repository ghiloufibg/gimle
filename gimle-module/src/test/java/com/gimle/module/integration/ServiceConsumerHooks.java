package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Looks up a {@link Greeter} on {@code onStart} and records the result for the test to inspect. */
public class ServiceConsumerHooks implements ModuleLifecycleHooks {

  public static final AtomicReference<Optional<String>> LOOKED_UP_GREETING =
      new AtomicReference<>(Optional.empty());

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    LOOKED_UP_GREETING.set(ctx.lookupService(Greeter.class).map(Greeter::greet));
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
