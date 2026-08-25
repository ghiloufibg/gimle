package com.gimle.agent;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.os.VolumeHandle;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the agent tracks for one instance the control plane has assigned to this node: the
 * work order itself, the worker JVM supervising it, that worker's control-channel listener, and
 * (once the spawned worker connects) the connection used to drive it up and read its reported
 * lifecycle state back. {@code lifecycleState} is updated from {@code ModuleStateChanged} messages
 * as they arrive; {@code connection} starts {@code null} and is filled in asynchronously once the
 * spawned worker JVM actually connects, so creating this instance never blocks the assignment-poll
 * loop on a slow-starting JVM. {@code assigned} is the one field here that is neither {@code final}
 * nor {@code volatile}-but-otherwise-fixed-at-construction -- see its own javadoc below for why.
 *
 * <p>{@code fabricWorkerId}/{@code fabricUdsPath}/{@code fabricTcpAddress} are populated from the
 * worker's {@code Hello} handshake -- the addressing needed to advertise a dialable {@link
 * com.gimle.fabric.catalog.ServiceEndpoint} once this instance later reports a {@code
 * ServiceRegistered}.
 *
 * <p>{@code assigned} is not {@code final}: {@code AgentMain}'s rename branch (a {@link
 * AssignedInstance#renamedFromInstanceIndex()} hint matching an already-supervised key) updates it
 * in place to retarget this exact same live instance onto a new index, without touching {@link
 * #supervisor}/{@link #connection}/{@link #lifecycleState} or any other field here -- the whole
 * point of a rename is that nothing about the running process itself changes.
 */
final class SupervisedInstance {

  volatile AssignedInstance assigned;
  final WorkerProcessSupervisor supervisor;
  final ControlChannelServer server;
  final ModuleDescriptor descriptor;

  /**
   * The {@code deploymentName#instanceIndex} key of the worker JVM actually hosting this instance
   * -- its own key for an instance that got a freshly-spawned worker, but a <em>different</em>
   * already-supervised instance's key when {@code AgentMain#installIntoExistingWorker} packed this
   * one onto an existing Tier 1 worker for density instead of spawning a new JVM. {@code
   * AgentLogServer} needs this to find the right {@code workers/<workerKey>/} directory on disk --
   * without it, a log request for a density-packed instance would look under a directory keyed by
   * that instance's own name/index, which was never created since no worker was ever spawned for it
   * specifically. {@code null} for the handful of unit tests that construct a {@link
   * SupervisedInstance} directly with no real worker behind it at all.
   */
  final String workerKey;

  volatile WorkerConnection connection;
  volatile String lifecycleState = "INSTALLED";
  volatile String fabricWorkerId;
  volatile String fabricUdsPath = "";
  volatile InetSocketAddress fabricTcpAddress;

  /**
   * The worker JVM's own self-reported resource usage, from its most recently received {@code
   * MetricsReport} -- zero until the first report arrives, same "degrade, don't fail" posture as
   * every other optional field here. Feeds {@code AutoscaleReconciler}'s CPU-utilization math,
   * which previously always saw zero since nothing on the worker side ever sent this message.
   */
  volatile long cpuMillicoresUsed;

  volatile long memoryBytesUsed;

  /**
   * Same "zero until the first report arrives" posture as {@link #cpuMillicoresUsed} above -- the
   * worker's own {@code metricsReportLoop} computes these as rates (requests/errors per second
   * since its last tick) and a raw queue depth off its {@code BoundedModuleScheduler}, so this
   * field is a direct copy of what the worker already computed, not derived here.
   */
  volatile double requestRatePerSecond;

  volatile double errorRatePerSecond;

  volatile int queueDepth;

  /**
   * This module instance's own self-reported ports (via {@code ModuleContext#reportPort}), from its
   * most recently received {@code MetricsReport} -- the module-hosted analog of {@link
   * SupervisedVessel#allocatedPorts}, empty until the module's hook code reports anything (which
   * the overwhelming majority of modules never do). Empty until the first report arrives, same
   * "degrade, don't fail" posture as every other optional field here.
   */
  volatile Map<String, Integer> ports = Map.of();

  /**
   * Present only when {@link #descriptor} declares {@code volume:} and allocation succeeded --
   * recorded here so {@code stopInstance} can release the exact same handle on permanent removal
   * without needing to recompute it (and without ever calling {@code VolumeManager.release} on a
   * rolling-update teardown-then-replace, which must not happen -- see {@code
   * AgentMain.stopInstance}'s own {@code releaseVolume} parameter).
   */
  volatile Optional<VolumeHandle> volumeHandle = Optional.empty();

  /**
   * The last sampled on-disk size of this instance's volume, and when it was sampled -- refreshed
   * lazily by {@code AgentMain#observationJson} at most once per sampling window rather than on
   * every heartbeat tick, since walking a large volume tree every few seconds would cost real I/O
   * for a soft, advisory observation. Both stay 0 for the overwhelming majority of instances, which
   * hold no volume at all.
   */
  volatile long volumeUsageBytes;

  volatile long volumeUsageSampledAtMillis;

  /**
   * The Sleipnir AOT cache key this instance's worker was spawned against, if any -- {@code empty}
   * when the worker started uncached (no eligible cache existed yet, or its classpath was
   * ineligible). {@code SleipnirCache#sweep} reads this across every live instance to decide which
   * on-disk cache entries are still referenced by a running worker and must survive the sweep.
   */
  volatile Optional<String> aotCacheKey = Optional.empty();

  /** {@code workerKey} defaults to {@code null} -- see that field's own javadoc. */
  SupervisedInstance(
      AssignedInstance assigned,
      WorkerProcessSupervisor supervisor,
      ControlChannelServer server,
      ModuleDescriptor descriptor) {
    this(assigned, supervisor, server, descriptor, null);
  }

  SupervisedInstance(
      AssignedInstance assigned,
      WorkerProcessSupervisor supervisor,
      ControlChannelServer server,
      ModuleDescriptor descriptor,
      String workerKey) {
    this.assigned = assigned;
    this.supervisor = supervisor;
    this.server = server;
    this.descriptor = descriptor;
    this.workerKey = workerKey;
  }
}
