package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
   * {@code requestRatePerSecond}/{@code errorRatePerSecond}/{@code queueDepth} feed autoscaling
   * decisions; a worker that hasn't wired request-rate collection yet can report {@code 0}s via the
   * three-argument constructor below instead of inventing values it doesn't have. {@code
   * errorRatePerSecond} was added after the other two; the pre-existing five-argument constructor
   * is preserved as an overload defaulting it to {@code 0} so no earlier call site needs updating.
   * {@code ports}, keyed by the name a hosted module reported it under via {@code
   * ModuleContext#reportPort}, was added last -- every earlier constructor shape is preserved as an
   * overload defaulting it to an empty map, matching this same record's own precedent for {@code
   * errorRatePerSecond}. Empty for the overwhelming majority of modules, which never call {@code
   * reportPort} at all.
   */
  record MetricsReport(
      ModuleId id,
      long cpuMillicoresUsed,
      long memoryBytesUsed,
      double requestRatePerSecond,
      int queueDepth,
      double errorRatePerSecond,
      Map<String, Integer> ports)
      implements ControlMessage {

    public MetricsReport {
      if (ports == null) {
        throw new IllegalArgumentException("ports must not be null; use Map.of()");
      }
      ports = Map.copyOf(ports);
    }

    public MetricsReport(ModuleId id, long cpuMillicoresUsed, long memoryBytesUsed) {
      this(id, cpuMillicoresUsed, memoryBytesUsed, 0.0, 0, 0.0, Map.of());
    }

    public MetricsReport(
        ModuleId id,
        long cpuMillicoresUsed,
        long memoryBytesUsed,
        double requestRatePerSecond,
        int queueDepth) {
      this(id, cpuMillicoresUsed, memoryBytesUsed, requestRatePerSecond, queueDepth, 0.0, Map.of());
    }

    /** The pre-{@code ports} full-detail shape, kept for existing call sites. */
    public MetricsReport(
        ModuleId id,
        long cpuMillicoresUsed,
        long memoryBytesUsed,
        double requestRatePerSecond,
        int queueDepth,
        double errorRatePerSecond) {
      this(
          id,
          cpuMillicoresUsed,
          memoryBytesUsed,
          requestRatePerSecond,
          queueDepth,
          errorRatePerSecond,
          Map.of());
    }
  }

  /**
   * Sent by a worker every time one of its hosted instances crosses a lifecycle transition -- the
   * durable counterpart to {@link ModuleStateChanged}, which the agent relays but never retains.
   * The agent proposes {@code AppendInstanceEvent} against the control-plane's state store on
   * receipt; a worker between agent restarts loses at most the events from that gap, the same
   * "brief gap tolerated" posture {@code MetricsReport}/heartbeats already have.
   */
  record InstanceEventOccurred(InstanceEvent event) implements ControlMessage {}

  /**
   * Sent by a worker the moment it registers (or tears down) a service export. The agent folds
   * these into its own service catalog, gossips them cluster-wide, and relays catalog deltas it
   * learns from gossip back down to its supervised workers over this same per-instance channel.
   */
  record ServiceRegistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record ServiceUnregistered(ModuleId moduleId, ServiceExport export) implements ControlMessage {}

  record Pong(String correlationId) implements ControlMessage {}

  /**
   * A worker's own periodic Micrometer snapshot / OpenTelemetry span batch, pre-serialized as
   * NDJSON by the worker itself -- the agent doesn't own the {@code MeterRegistry}/span batch these
   * were built from, only the text a worker already sent over this control channel, the one channel
   * that crosses the process boundary at all (workers have no outbound network identity of their
   * own -- see {@code MuninnServer}'s own javadoc). {@code workerId} is this worker's own {@code
   * Hello#workerId}, matching the {@code {nodeId}:{workerId}} shape the agent's relay ships under.
   * A trivial relay on the agent side: {@code AgentMain} hands {@code ndjsonPayload} straight to
   * {@code MuninnShipper#shipPreparedBatch} with no re-serialization.
   */
  record MetricsSnapshot(String workerId, String ndjsonPayload) implements ControlMessage {}

  /** See {@link #MetricsSnapshot}; the same shape for a worker's own exported span batch. */
  record TracesSnapshot(String workerId, String ndjsonPayload) implements ControlMessage {}

  /**
   * A hosted module's narrow, whitelisted read-back into the control plane's own HTTP API, relayed
   * through this worker's agent -- a worker JVM has no outbound network identity of its own, only
   * its agent does (a real mTLS client certificate minted at bootstrap), so the module can never
   * make this call itself. {@code path} is an HTTP path only (e.g. {@code GET
   * /endpoints/{deployment}}), never interpreted by the worker -- the agent alone decides whether
   * it's one of the paths this mechanism is allowed to reach, and makes the real call on the
   * module's behalf if so. {@code correlationId} lets the worker match this request to its eventual
   * {@link RelayControlPlaneResult}, since the control channel carries other unrelated traffic
   * concurrently.
   */
  record RelayControlPlaneRead(String correlationId, String path) implements ControlMessage {}

  /**
   * A hosted operator module's status report for one custom resource, relayed through its agent as
   * {@code PUT /resources/{kindName}/{name}/status} under the instance's own workload-identity
   * token -- the one write the relay mechanism carries, deliberately typed field-by-field rather
   * than as a raw path+body so the agent can validate each segment before assembling the real
   * request. {@code tenantId} is blank for an untenanted (Cluster-scoped) resource. Answered with
   * the same {@link RelayControlPlaneResult} a read gets, matched by {@code correlationId}.
   */
  record RelayResourceStatusPut(
      String correlationId, String kindName, String tenantId, String name, String statusJson)
      implements ControlMessage {}

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

  /**
   * {@code dataDirectories} maps each of the module's declared volume names to the host path that
   * named volume was allocated at -- resolved by the agent (which alone knows the placement
   * identity a volume path is keyed by) via {@code VolumeManager.allocate} before this message is
   * sent, and populated on the worker's {@link
   * com.gimle.module.lifecycle.ModuleContext#dataDirectory(String)} the moment this instance's
   * {@code ModuleContext} is created -- {@code resolve()}, not {@code start()}, since a hook may
   * need it as early as {@code onInstall}. Empty for every module that declares no {@code
   * volumes:}; the two-argument constructor preserves that shape.
   */
  record ResolveModule(String correlationId, ModuleId id, Map<String, String> dataDirectories)
      implements ControlMessage {

    public ResolveModule {
      dataDirectories = Map.copyOf(dataDirectories);
    }

    public ResolveModule(String correlationId, ModuleId id) {
      this(correlationId, id, Map.of());
    }
  }

  /**
   * Retargets an already-installed instance's placement identity in place -- {@code
   * DeploymentReconciler#handleSurge}'s promotion step, moving a proven-healthy surge instance onto
   * its permanent index without a restart. Updates only what {@link InstallModule}'s own {@code
   * deploymentName}/{@code instanceIndex} log-tagging identity established (via {@code
   * InstanceIdentityRegistry}); the module itself is never resolved, started, or stopped again.
   */
  record RenameInstance(String correlationId, ModuleId id, String deploymentName, int instanceIndex)
      implements ControlMessage {}

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

  /**
   * The full set of configuration and secret keys that currently exist upstream for every instance
   * this worker hosts -- the authoritative answer to "what should still be visible", which the
   * worker applies by dropping any locally-held key not named here. This is what makes a deleted
   * ConfigMap/Secret key actually disappear from a running instance instead of staying readable
   * until its next restart.
   *
   * <p>A whole-set assertion rather than a per-key removal event, and re-sent every relay tick
   * rather than only when something was deleted: a removal event that a disconnected worker missed
   * would leave the revoked value live forever, whereas re-asserting the whole set converges from
   * any starting state -- including a worker that reconnected mid-deletion or a relay that skipped
   * ticks entirely. The keys of every instance sharing one worker are carried together, since a
   * Tier 1 worker hosts several instances behind one channel and one instance's set alone would
   * wrongly retract another's.
   */
  record ConfigKeysRetained(List<String> keys) implements ControlMessage {

    public ConfigKeysRetained {
      if (keys == null) {
        throw new IllegalArgumentException("keys must not be null");
      }
      keys = List.copyOf(keys);
    }
  }

  /**
   * The agent relaying its most recent poll of the control plane's own {@code NetworkPolicySpec}s
   * down to this worker -- a worker has no outbound network identity of its own to poll the control
   * plane directly, the same reason {@link CatalogUpdate} and {@link ConfigDelivered} already
   * travel this same direction over this same channel. A full replacement of the previously-held
   * set each time, not a delta, matching {@code CatalogUpdate}'s own eventually-consistent,
   * level-triggered posture: a missed message self-heals on the agent's next poll tick rather than
   * leaving stale policy state behind. Carries both tenant-wide and per-deployment-scoped policies
   * -- {@link NetworkPolicyRule#deploymentNames()} distinguishes them; the receiving {@code
   * FabricServer} resolves each inbound call's own target deployment to decide which apply.
   *
   * <p>{@code denyByDefaultTenantIds} carries the tenants whose declared isolation posture closes
   * them to uncovered cross-tenant traffic. It rides here rather than on its own message because a
   * worker can only decide an uncovered call correctly when it holds both halves at once: a
   * posture that arrived without the rules it defers to would deny calls a policy actually permits.
   */
  record NetworkPoliciesUpdated(
      List<NetworkPolicyRule> rules, Set<String> denyByDefaultTenantIds)
      implements ControlMessage {

    public NetworkPoliciesUpdated {
      if (rules == null) {
        throw new IllegalArgumentException("rules must not be null");
      }
      if (denyByDefaultTenantIds == null) {
        throw new IllegalArgumentException("denyByDefaultTenantIds must not be null");
      }
      rules = List.copyOf(rules);
      denyByDefaultTenantIds = Set.copyOf(denyByDefaultTenantIds);
    }
  }

  /**
   * The agent's answer to a {@link RelayControlPlaneRead}: {@code status} and {@code body} are
   * exactly what the control plane's own HTTP API returned, or a status the agent synthesized
   * itself -- {@code 403} for a path the whitelist rejected before any real call was made, {@code
   * 502} for a transport failure reaching the control plane. Never a thrown exception on the worker
   * side: a hosted module sees this as an ordinary result, not a channel-level failure.
   */
  record RelayControlPlaneResult(String correlationId, int status, String body)
      implements ControlMessage {}
}
