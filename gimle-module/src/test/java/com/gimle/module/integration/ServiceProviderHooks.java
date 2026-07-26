package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Registers a {@link Greeter} on {@code on_start}. See {@link RecordingHooks} for why this lives
 * here.
 */
public class ServiceProviderHooks implements ModuleLifecycleHooks {

  @Override
  public void on_install(ModuleContext ctx) {}

  @Override
  public void on_start(ModuleContext ctx) {
    ctx.register_service(Greeter.class, () -> "hello from provider");
  }

  @Override
  public void on_stop(ModuleContext ctx) {}

  @Override
  public void on_uninstall(ModuleContext ctx) {}
}
