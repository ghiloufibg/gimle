package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;

/**
 * Agent&harr;worker control-channel frames: install/start/stop commands and periodic
 * health/metrics/catalog reports. Deliberately simpler than the fabric's compact binary codec used
 * for per-request service invocations — this channel carries occasional control traffic, not
 * per-request calls, so a trivially-debuggable protocol is preferable. Module state travels as a
 * plain {@code String} rather than {@code gimle-module}'s own state enum, since {@code gimle-core}
 * has no dependency on {@code gimle-module} and the agent only needs to track and relay the state,
 * not interpret it.
 */
public sealed interface ControlMessage {

  // Worker -> Agent
  /**
   * {@code fabricUdsPath}/{@code fabricTcpHost}/{@code fabricTcpPort} are this worker's own service
   * listener addresses, which the agent needs to advertise a dialable endpoint once the worker
   * reports a {@link ServiceRegistered}. The two-argument constructor leaves them empty/{@code 0}
   * for a worker that hasn't wired the fabric in yet.
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
   * {@code requestRatePerSecond}/{@code queueDepth} feed autoscaling decisions; a worker that
   * hasn't wired request-rate collection yet can report {@code 0}/{@code 0} via the three-argument
   * constructor below instead of inventing values it doesn't have.
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
   * Sent by a worker the moment it registers (or tears down) a service export. The agent folds
   * these into its own service catalog, gossips them cluster-wide, and relays catalog deltas it
   * learns from gossip back down to its supervised workers over this same per-instance channel.
   */
  record ServiceRegistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record ServiceUnregistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record Pong(String correlationId) implements ControlMessage {}

  // Agent -> Worker
  /**
   * {@code deploymentName}/{@code instanceIndex} are this instance's placement identity, already
   * known to the agent via {@link AssignedInstance}. They let the worker tag every log line it
   * emits while handling this instance's requests as application (rather than platform) log output.
   * The two-argument constructor leaves them blank/{@code -1} for a caller that hasn't wired
   * instance identity through yet; the worker then registers the module without that log tagging.
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
   * The agent relaying one catalog delta it learned — from one of its own other supervised workers,
   * or from gossip about a different node entirely — down to this worker's locally cached service
   * catalog. Unlike {@link ServiceRegistered}/{@link ServiceUnregistered}, which always describe
   * the sending worker's own export and travel worker-to-agent, this message names an arbitrary
   * {@code (nodeId, workerId, moduleId)} and so carries the full addressing the worker needs to
   * reach it: {@code udsPath} (empty for a remote node) and the cross-machine {@code
   * tcpHost}/{@code tcpPort}.
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
   * The agent relaying one tenant-scoped configuration or secret value down to this worker, fetched
   * from the control plane (already decrypted server-side if it was a secret) and forwarded over
   * this same per-instance channel — no plaintext secret ever touches the agent's own disk, only
   * this in-memory relay. {@code wasEncrypted} is diagnostic only; {@code value} is always the
   * plaintext the module should see.
   */
  record ConfigDelivered(String key, String value, boolean wasEncrypted)
      implements ControlMessage {}
}
