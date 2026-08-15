package com.gimle.core.vessel;

import java.util.OptionalInt;

/**
 * One entry of a vessel's {@code env:} map: a plain literal, a tenant-scoped Fafnir secret key
 * resolved by the agent at spawn time, or a request for the agent to allocate (or simply use, for a
 * fixed value) a port and export its number as this variable.
 */
public sealed interface VesselEnvValue
    permits VesselEnvValue.Literal, VesselEnvValue.SecretRef, VesselEnvValue.PortAllocation {

  /** A plain string, exported to the process exactly as written. */
  record Literal(String value) implements VesselEnvValue {
    public Literal {
      if (value == null) {
        throw new IllegalArgumentException("literal env value must not be null");
      }
    }
  }

  /** Resolved from Fafnir, scoped to the vessel's own tenant, at spawn time. */
  record SecretRef(String key) implements VesselEnvValue {
    public SecretRef {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("secret key must not be blank");
      }
    }
  }

  /**
   * {@code fixedPort} empty means {@code {port: dynamic}} -- the agent picks a free port; present
   * means {@code {port: <n>}} -- the agent uses exactly that port, no allocation involved. Either
   * way the agent exports the number under this env var's name and additionally records it (keyed
   * by that same name) for the endpoint registry.
   */
  record PortAllocation(OptionalInt fixedPort) implements VesselEnvValue {
    public PortAllocation {
      if (fixedPort == null) {
        throw new IllegalArgumentException("fixedPort must be OptionalInt.empty(), not null");
      }
      if (fixedPort.isPresent() && (fixedPort.getAsInt() < 1 || fixedPort.getAsInt() > 65535)) {
        throw new IllegalArgumentException("fixed port out of range: " + fixedPort.getAsInt());
      }
    }
  }
}
