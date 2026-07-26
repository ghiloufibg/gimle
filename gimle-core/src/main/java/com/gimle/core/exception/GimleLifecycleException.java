package com.gimle.core.exception;

import com.gimle.core.module.ModuleId;

/**
 * A module lifecycle hook threw, or an illegal state transition was requested. Thrown synchronously
 * for gating transitions ({@code onInstall}/{@code onStart}) whose failure must abort the
 * transition; teardown transitions ({@code onStop}/{@code onUninstall}) still construct this
 * exception to record in a {@code TransitionFailed} event, but the state machine does not let a
 * misbehaving teardown hook block resource disposal.
 */
public class GimleLifecycleException extends RuntimeException {

  private GimleLifecycleException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleLifecycleException hookFailed(
      ModuleId moduleId, String hookName, Throwable cause) {
    return new GimleLifecycleException(
        "module " + moduleId + " lifecycle hook '" + hookName + "' threw an exception", cause);
  }

  public static GimleLifecycleException illegalTransition(
      ModuleId moduleId, String from, String to) {
    return new GimleLifecycleException(
        "module " + moduleId + " cannot transition from " + from + " to " + to, null);
  }
}
