package com.example.webui.provider;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once {@link WebUiHooks#onStart} has bound and started the embedded HTTP server --
 * shares that static flag with the hooks class the same way the platform's own greeter-provider
 * example does. */
public final class WebUiReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return WebUiHooks.ready.get();
  }
}
