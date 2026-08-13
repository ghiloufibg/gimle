package com.gimle.agent;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.os.VolumeHandle;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Everything the agent tracks for one instance the control plane has assigned to this node: the
 * work order itself, the worker JVM supervising it, that worker's control-channel listener, and
 * (once the spawned worker connects) the connection used to drive it up and read its reported
 * lifecycle state back. {@code lifecycleState} is updated from {@code ModuleStateChanged} messages
 * as they arrive; {@code connection} starts {@code null} and is filled in asynchronously once the
 * spawned worker JVM actually connects, so creating this instance never blocks the assignment-poll
 * loop on a slow-starting JVM.
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
   * Present only when {@link #descriptor} declares {@code volume:} and allocation succeeded --
   * recorded here so {@code stopInstance} can release the exact same handle on permanent removal
   * without needing to recompute it (and without ever calling {@code VolumeManager.release} on a
   * rolling-update teardown-then-replace, which must not happen -- see {@code
   * AgentMain.stopInstance}'s own {@code releaseVolume} parameter).
   */
  volatile Optional<VolumeHandle> volumeHandle = Optional.empty();

  SupervisedInstance(
      AssignedInstance assigned,
      WorkerProcessSupervisor supervisor,
      ControlChannelServer server,
      ModuleDescriptor descriptor) {
    this.assigned = assigned;
    this.supervisor = supervisor;
    this.server = server;
    this.descriptor = descriptor;
  }
}
