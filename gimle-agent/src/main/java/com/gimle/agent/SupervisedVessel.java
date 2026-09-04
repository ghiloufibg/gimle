package com.gimle.agent;

import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.VolumeHandle;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything this agent tracks for one vessel instance -- the {@link SupervisedInstance} analog for
 * a plain runnable-jar process rather than a Java module: no {@code ControlChannelServer}, no
 * {@code ModuleDescriptor}, no worker connection, because a vessel never speaks Gimlé's own control
 * protocol at all. {@code lifecycleState} mirrors the same {@code "STARTING"}/{@code "ACTIVE"}/
 * {@code "FAILED"} vocabulary {@link SupervisedInstance} uses (and {@link
 * AgentMain#observationJson} already derives {@code alive}/{@code ready} from), driven here by
 * {@link VesselProber} results instead of a worker's own {@code ModuleStateChanged} messages.
 */
final class SupervisedVessel {

  volatile AssignedInstance assigned;
  final VesselSpec vessel;
  final VesselProcessSupervisor supervisor;
  final ResourceLimitHandle resourceLimitHandle;

  /**
   * Not {@code final}: a crash-triggered respawn ({@link AgentMain#onVesselRespawned}) resets this
   * to the moment the fresh process actually started, so every probe rung's own {@code
   * initialDelaySeconds} restarts counting from there rather than from the original spawn.
   */
  volatile Instant startedAt;

  /** {@code env}-var-name -> allocated/fixed port, empty for a vessel declaring none. */
  final Map<String, Integer> allocatedPorts;

  /**
   * The persistent volumes allocated for this vessel's {@code {volume: ...}} env entries, released
   * only on genuinely permanent removal -- the same posture {@code
   * SupervisedInstance#volumeHandles} documents for module hosting.
   */
  final List<VolumeHandle> volumeHandles;

  volatile String lifecycleState = "STARTING";

  /**
   * Set once {@link VesselProcessSupervisor}'s own {@code RestartTracker} exhausts its restart
   * budget for this instance -- {@link AgentMain#reconcileVesselAssignment} reads this to stop
   * calling {@link AgentMain#startVesselInstance} for the same unchanged assignment on every
   * subsequent poll tick. Without it, the reconcile loop finds this key missing from {@code
   * supervisedVessels} (the old behavior removed the entry outright on exhaustion) and treats that
   * as "never started," spawning a brand-new supervisor with a brand-new budget -- silently
   * defeating the whole backoff/give-up policy the ERROR log this fires alongside claims already
   * happened. Cleared only by {@link AgentMain#requiresVesselReplacement} actually finding a
   * changed assignment (a real rollout, i.e. an explicit operator action), never by the passage of
   * time.
   */
  volatile boolean restartBudgetExhausted;

  SupervisedVessel(
      AssignedInstance assigned,
      VesselSpec vessel,
      VesselProcessSupervisor supervisor,
      ResourceLimitHandle resourceLimitHandle,
      Map<String, Integer> allocatedPorts,
      List<VolumeHandle> volumeHandles,
      Instant startedAt) {
    this.assigned = assigned;
    this.vessel = vessel;
    this.supervisor = supervisor;
    this.resourceLimitHandle = resourceLimitHandle;
    this.allocatedPorts = Map.copyOf(allocatedPorts);
    this.volumeHandles = List.copyOf(volumeHandles);
    this.startedAt = startedAt;
  }
}
