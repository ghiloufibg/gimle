package com.gimle.core.vessel;

/**
 * One rung of a vessel's liveness/readiness probe ladder above the always-available "process still
 * running" floor -- see {@link VesselProbes} for where that floor (an absent probe) is represented.
 * Dialed by the agent from outside the process, never a Java interface invoked in-JVM: a vessel is
 * an opaque black-box OS process the agent doesn't control the classpath of. {@code
 * initialDelaySeconds} (default {@code 0} via each variant's own back-compat constructor) delays
 * the first check after the process starts, the same role {@code
 * ModuleDescriptor.HealthProbes.initialDelay()} plays for module probes.
 */
public sealed interface VesselProbeSpec permits VesselProbeSpec.Tcp, VesselProbeSpec.Http {

  int initialDelaySeconds();

  /** Can the agent open (and immediately close) a TCP connection to the vessel's own port? */
  record Tcp(int initialDelaySeconds) implements VesselProbeSpec {
    public Tcp {
      if (initialDelaySeconds < 0) {
        throw new IllegalArgumentException(
            "initialDelaySeconds must not be negative: " + initialDelaySeconds);
      }
    }

    public Tcp() {
      this(0);
    }
  }

  /** Does an HTTP GET to {@code http://localhost:<port>/path} return a 2xx status? */
  record Http(String path, int initialDelaySeconds) implements VesselProbeSpec {
    public Http {
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("http probe path must not be blank");
      }
      if (initialDelaySeconds < 0) {
        throw new IllegalArgumentException(
            "initialDelaySeconds must not be negative: " + initialDelaySeconds);
      }
    }

    public Http(String path) {
      this(path, 0);
    }
  }
}
