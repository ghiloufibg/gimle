package com.gimle.core.vessel;

import com.gimle.core.module.ReclaimPolicy;
import java.util.OptionalInt;

/**
 * One entry of a vessel's {@code env:} map: a plain literal, a tenant-scoped Fafnir secret key
 * resolved by the agent at spawn time, a request for the agent to allocate (or simply use, for a
 * fixed value) a port and export its number as this variable, or a request for a persistent volume
 * whose resolved host path is exported as this variable -- the vessel analogue of a module's own
 * {@code volumes:}/{@code dataDirectory(name)}, riding the env map exactly the way ports already do
 * since an opaque external process can only ever learn a path through its environment.
 */
public sealed interface VesselEnvValue
    permits VesselEnvValue.Literal,
        VesselEnvValue.SecretRef,
        VesselEnvValue.PortAllocation,
        VesselEnvValue.VolumeMount {

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

  /**
   * A persistent local-disk volume, allocated by the agent at spawn time (keyed by the instance's
   * own placement identity plus this entry's env-var name, so it survives restarts and rolling
   * updates exactly like a module volume) and exported to the process as this variable's value --
   * the resolved host path. {@code sizeBytes} stays advisory and {@code reclaimPolicy} defaults to
   * {@link ReclaimPolicy#RETAIN}, both matching {@code VolumeRequest}'s own posture.
   */
  record VolumeMount(long sizeBytes, ReclaimPolicy reclaimPolicy) implements VesselEnvValue {
    public VolumeMount {
      if (sizeBytes <= 0) {
        throw new IllegalArgumentException("sizeBytes must be positive: " + sizeBytes);
      }
      if (reclaimPolicy == null) {
        throw new IllegalArgumentException("reclaimPolicy must not be null");
      }
    }

    public VolumeMount(long sizeBytes) {
      this(sizeBytes, ReclaimPolicy.RETAIN);
    }
  }
}
