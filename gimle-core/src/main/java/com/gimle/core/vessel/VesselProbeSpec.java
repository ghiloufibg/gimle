package com.gimle.core.vessel;

import java.util.Optional;

/**
 * One rung of a vessel's liveness/readiness probe ladder above the always-available "process still
 * running" floor -- see {@link VesselProbes} for where that floor (an absent probe) is represented.
 * Dialed by the agent from outside the process, never a Java interface invoked in-JVM: a vessel is
 * an opaque black-box OS process the agent doesn't control the classpath of. {@code
 * initialDelaySeconds} (default {@code 0} via each variant's own back-compat constructor) delays
 * the first check after the process starts, the same role {@code
 * ModuleDescriptor.HealthProbes.initialDelay()} plays for module probes.
 *
 * <p>{@code portName} names which {@code {port: ...}} env entry ({@link
 * VesselEnvValue.PortAllocation}) this rung dials, by its env-var key. It's required once a vessel
 * declares more than one such entry -- see {@link VesselSpec}'s own compact constructor -- and
 * optional (falling back to the sole declared port) otherwise, so a single-port vessel's probes
 * need not name what there is only one of.
 */
public sealed interface VesselProbeSpec permits VesselProbeSpec.Tcp, VesselProbeSpec.Http {

  int initialDelaySeconds();

  Optional<String> portName();

  /** Can the agent open (and immediately close) a TCP connection to the vessel's own port? */
  record Tcp(Optional<String> portName, int initialDelaySeconds) implements VesselProbeSpec {
    public Tcp {
      portName = portName == null ? Optional.empty() : portName;
      if (initialDelaySeconds < 0) {
        throw new IllegalArgumentException(
            "initialDelaySeconds must not be negative: " + initialDelaySeconds);
      }
    }

    public Tcp() {
      this(Optional.empty(), 0);
    }

    public Tcp(int initialDelaySeconds) {
      this(Optional.empty(), initialDelaySeconds);
    }
  }

  /** Does an HTTP GET to {@code http://localhost:<port>/path} return a 2xx status? */
  record Http(String path, Optional<String> portName, int initialDelaySeconds)
      implements VesselProbeSpec {
    public Http {
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("http probe path must not be blank");
      }
      portName = portName == null ? Optional.empty() : portName;
      if (initialDelaySeconds < 0) {
        throw new IllegalArgumentException(
            "initialDelaySeconds must not be negative: " + initialDelaySeconds);
      }
    }

    public Http(String path) {
      this(path, Optional.empty(), 0);
    }

    public Http(String path, int initialDelaySeconds) {
      this(path, Optional.empty(), initialDelaySeconds);
    }
  }
}
