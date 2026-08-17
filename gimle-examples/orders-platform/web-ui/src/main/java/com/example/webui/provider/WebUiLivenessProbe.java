package com.example.webui.provider;

import com.gimle.module.probe.LivenessProbe;

/** No failure mode of its own -- there's nothing this demo app does that can wedge the process
 * without the JVM itself going down, which the worker would already notice another way. If the
 * HTTP server itself fails to bind, {@link WebUiHooks#onStart} throws and the module never
 * reaches ACTIVE in the first place, so there's nothing left for liveness to catch afterward. */
public final class WebUiLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
