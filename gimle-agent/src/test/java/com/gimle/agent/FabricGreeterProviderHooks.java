package com.gimle.agent;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Registers a real {@link FabricGreeter} on {@code onStart} -- exercised as a real worker
 * subprocess by {@link FabricCrossProcessIntegrationTest}, not in-process. See {@code
 * com.gimle.module.integration.RecordingHooks} for why a fixture's lifecycle hooks class lives on
 * the outer test classpath rather than bundled inside the fixture jar.
 */
public class FabricGreeterProviderHooks implements ModuleLifecycleHooks {

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(FabricGreeter.class, name -> "hello " + name + " from provider");
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
