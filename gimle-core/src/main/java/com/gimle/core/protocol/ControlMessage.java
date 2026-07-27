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
  /**
   * {@code deploymentName}/{@code instanceIndex} (log-explorer-design.md §3) are this instance's
   * placement identity, already known to the agent via {@code AssignedInstance} -- carried here so
   * the worker can tag every log line it emits while handling this instance's own requests as
   * APPLICATION rather than PLATFORM. Blank/{@code -1} (via the two-argument constructor) for a
   * caller that hasn't wired instance identity through yet, the same "absent means today's
   * unchanged behavior" precedent {@link Hello}'s two-argument constructor already established --
   * the worker simply registers the module without MDC tagging in that case.
   */
  record InstallModule(
      String correlationId, String artifactPath, String deploymentName, int instanceIndex)
      implements ControlMessage {

    public InstallModule(String correlationId, String artifactPath) {
      this(correlationId, artifactPath, "", -1);
    }
  }

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

  /**
   * The agent relaying one tenant-scoped configuration or secret value down to this worker (Phase 5
   * design §6.3), fetched from the control plane (already decrypted server-side if it was a secret)
   * and forwarded over this same per-instance channel -- no plaintext secret ever touches the
   * agent's own disk, only this in-memory relay. {@code wasEncrypted} is diagnostic only (the
   * worker never re-encrypts or re-decrypts anything); {@code value} here is always the plaintext
   * the module should see.
   */
  record ConfigDelivered(String key, String value, boolean wasEncrypted)
      implements ControlMessage {}
}
