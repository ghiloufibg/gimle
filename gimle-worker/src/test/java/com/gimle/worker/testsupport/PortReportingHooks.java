package com.gimle.worker.testsupport;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;

/**
 * Instantiated by reflection inside the fixture module's own dynamically-created {@code
 * ModuleLayer}, the same "hooks class lives outside the built jar, on the parent unnamed module's
 * classpath" shape {@code ServiceProviderHooks} already establishes in {@code gimle-module}'s own
 * integration tests. {@code onStart} calling {@link ModuleContext#reportPort} mirrors exactly what
 * a real HTTP-serving module does the moment after it binds its own listener socket.
 */
public final class PortReportingHooks implements ModuleLifecycleHooks {

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.reportPort("HTTP_PORT", 8080);
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
