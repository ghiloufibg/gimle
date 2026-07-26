package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Registers a {@link Greeter} on {@code onStart}. See {@link RecordingHooks} for why this lives
 * here.
 */
public class ServiceProviderHooks implements ModuleLifecycleHooks {

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(Greeter.class, () -> "hello from provider");
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
