package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;

/**
 * Agent&harr;worker control-channel frames. Deliberately not {@code gimle-fabric}'s compact binary
 * codec (Phase 4's own per-request service-invocation transport) — this is a throwaway,
 * trivially-debuggable protocol for a handful of install/start/stop commands and periodic
 * health/metrics/catalog reports, not per-request traffic. Module state travels as a plain {@code
 * String} ({@code ModuleState#name()}), not {@code gimle-module}'s own enum: {@code gimle-core} has
 * no dependency on {@code gimle-module}, and the agent doesn't need to understand module states
 * beyond tracking/relaying them — only the worker's own internals need the real type.
 */
public sealed interface ControlMessage {

  // Worker -> Agent
  /**
   * {@code fabricUdsPath}/{@code fabricTcpHost}/{@code fabricTcpPort} (Phase 4 §7) are this
   * worker's own {@code FabricServer} listener addresses -- the agent needs them to advertise a
   * dialable {@code ServiceEndpoint} once this worker later reports a {@link ServiceRegistered};
   * empty/{@code 0} (via the two-argument constructor) for a worker that hasn't wired the fabric in
   * yet.
   */
  record Hello(
      String workerId, long pid, String fabricUdsPath, String fabricTcpHost, int fabricTcpPort)
      implements ControlMessage {

    public Hello(String workerId, long pid) {
      this(workerId, pid, "", "", 0);
    }
  }

  record Ack(String correlationId) implements ControlMessage {}

  record Nack(String correlationId, String reason) implements ControlMessage {}

  record ModuleStateChanged(ModuleId id, String state) implements ControlMessage {}

  record HealthReport(ModuleId id, boolean alive, boolean ready) implements ControlMessage {}

  /**
   * {@code requestRatePerSecond}/{@code queueDepth} (Phase 4 §10) are additive fields feeding
   * {@code AutoscaleReconciler}; a worker that hasn't wired request-rate collection yet reports
   * {@code 0}/{@code 0} via the three-argument constructor below rather than every existing call
   * site having to invent values it doesn't have.
   */
  record MetricsReport(
      ModuleId id,
      long cpuMillicoresUsed,
      long memoryBytesUsed,
      double requestRatePerSecond,
      int queueDepth)
      implements ControlMessage {

    public MetricsReport(ModuleId id, long cpuMillicoresUsed, long memoryBytesUsed) {
      this(id, cpuMillicoresUsed, memoryBytesUsed, 0.0, 0);
    }
  }

  /**
   * Sent by a worker the moment {@code ModuleContext.registerService}/its teardown counterpart runs
   * (Phase 4 §5): the agent folds these into its own {@code ServiceCatalog} entries and gossips
   * them cluster-wide, and relays deltas it learns from gossip back down to its own supervised
   * workers over this same per-instance channel — genuinely agent&harr;worker traffic, not a
   * control-plane concept smuggled in.
   */
  record ServiceRegistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record ServiceUnregistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record Pong(String correlationId) implements ControlMessage {}

  // Agent -> Worker
  record InstallModule(String correlationId, String artifactPath) implements ControlMessage {}

  record ResolveModule(String correlationId, ModuleId id) implements ControlMessage {}

  record StartModule(String correlationId, ModuleId id) implements ControlMessage {}

  record StopModule(String correlationId, ModuleId id) implements ControlMessage {}

  record UninstallModule(String correlationId, ModuleId id) implements ControlMessage {}

  record Ping(String correlationId) implements ControlMessage {}

  /**
   * The agent relaying one catalog delta it learned -- from one of its own other supervised
   * workers, or from gossip about a different node entirely -- down to this worker's locally cached
   * {@code ServiceCatalog} (design §5). Unlike {@link ServiceRegistered}/{@link
   * ServiceUnregistered} (which always describe "my own worker's own export" and travel
   * worker-&gt;agent), this one names an arbitrary {@code (nodeId, workerId, moduleId)} and so must
   * carry the full addressing the worker needs to actually reach it: {@code udsPath} (empty if
   * none, e.g. a remote node) and the cross-machine {@code tcpHost}/{@code tcpPort}.
   */
  record CatalogUpdate(
      String nodeId,
      String workerId,
      ModuleId moduleId,
      ServiceExport export,
      long version,
      boolean present,
      String udsPath,
      String tcpHost,
      int tcpPort)
      implements ControlMessage {}
}
