package com.gimle.module.integration;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Begins a request in {@code on_start} and never ends it — see {@link RecordingHooks} for why this
 * lives here.
 */
public class NeverDrainsHooks implements ModuleLifecycleHooks {

  @Override
  public void on_install(ModuleContext ctx) {}

  @Override
  public void on_start(ModuleContext ctx) {
    ctx.begin_request();
  }

  @Override
  public void on_stop(ModuleContext ctx) {}

  @Override
  public void on_uninstall(ModuleContext ctx) {}
}
