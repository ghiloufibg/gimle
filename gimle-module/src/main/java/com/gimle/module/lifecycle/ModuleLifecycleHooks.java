package com.gimle.module.lifecycle;

/**
 * Implemented by a hosted module's own lifecycle-hooks class, named in its descriptor. That class's
 * package must be {@code exports}ed (or {@code opens}ed) in the module's {@code module-info.java} —
 * {@link ModuleController} instantiates it reflectively from outside the module, and JPMS strong
 * encapsulation blocks that against a concealed package regardless of the constructor's own
 * visibility.
 */
public interface ModuleLifecycleHooks {

  void onInstall(ModuleContext ctx);

  void onStart(ModuleContext ctx);

  void onStop(ModuleContext ctx);

  void onUninstall(ModuleContext ctx);
}
