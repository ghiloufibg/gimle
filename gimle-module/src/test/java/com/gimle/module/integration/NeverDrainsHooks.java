package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Begins a request in {@code onStart} and never ends it — see {@link RecordingHooks} for why this
 * lives here.
 */
public class NeverDrainsHooks implements ModuleLifecycleHooks {

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.beginRequest();
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
