package com.gimle.module.lifecycle;

import java.util.Optional;

/**
 * The agent-mediated control-plane hole a {@link SimpleModuleContext} delegates to: the whitelisted
 * read {@link ModuleContext#relayControlPlaneRead} rides, plus the one write the relay mechanism
 * carries -- a custom resource's status report. Both travel the worker-agent control channel in
 * production ({@code gimle-worker} wires the real implementation); a directly-embedded controller
 * in a test gets {@link #unavailable()}, the same synthesized "not available" posture the read-only
 * relay function had before status reporting existed.
 */
public interface ControlPlaneRelayClient {

  ModuleContext.RelayResult read(String path);

  ModuleContext.RelayResult putResourceStatus(
      String kindName, Optional<String> tenantId, String name, String statusJson);

  static ControlPlaneRelayClient unavailable() {
    return new ControlPlaneRelayClient() {
      @Override
      public ModuleContext.RelayResult read(String path) {
        return new ModuleContext.RelayResult(
            501, "control-plane relay is not available on this context");
      }

      @Override
      public ModuleContext.RelayResult putResourceStatus(
          String kindName, Optional<String> tenantId, String name, String statusJson) {
        return new ModuleContext.RelayResult(
            501, "control-plane relay is not available on this context");
      }
    };
  }
}
