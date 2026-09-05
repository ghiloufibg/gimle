package com.gimle.controlplane.schedule;

import com.gimle.controlplane.node.NodeFreshness;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives the {@link NodeCandidate} list {@link Scheduler} places against from the current store
 * snapshot: every registered node that has heartbeated recently enough to still be answering,
 * carrying its latest reported capacity, its operator-set taints and cordon flag, and whether it
 * already runs the workload being placed.
 *
 * <p>One implementation shared by every reconciler that places instances and by the API server's
 * dry-run preview, rather than a copy per caller. That sharing is the point: a preview that built
 * its own candidate list could quietly disagree with what a real placement would decide -- a
 * differently-drawn node-darkness cutoff alone would be enough -- and a placement forecast an
 * operator cannot trust is worse than none at all.
 *
 * <p>Reads only; nothing here proposes a mutation, so a caller may run it purely to answer "where
 * would this land?" without changing anything.
 */
public final class NodeCandidateSource {

  private final StoreReader store;
  private final NodeFreshness freshness;
  private final Clock clock;

  public NodeCandidateSource(StoreReader store, Duration nodeDarkTimeout, Clock clock) {
    this.store = store;
    this.freshness = new NodeFreshness(nodeDarkTimeout);
    this.clock = clock;
  }

  /**
   * {@code nodesAlreadyRunningThisWorkload} is what each caller derives from whichever assignment
   * kind it owns (instance assignments, StatefulSet assignments, job runs) plus, for a caller
   * placing several replicas in one pass, the nodes that pass has already chosen -- it is the sole
   * input to each candidate's {@code alreadyRunsThisDeployment} flag, which only anti-affinity ever
   * reads. Pass an empty set for a workload kind that has no such notion.
   */
  public List<NodeCandidate> candidates(Set<String> nodesAlreadyRunningThisWorkload) {
    Instant now = clock.instant();
    Instant observingSince = store.nodeObservationWindowStart();
    List<NodeCandidate> candidates = new ArrayList<>();
    for (NodeRegistration registration : store.listNodeRegistrations()) {
      Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(registration.nodeId());
      if (heartbeat.isEmpty()) {
        // No capacity report to place against, whether because the node has never sent one or
        // because the store only just started collecting them -- either way there is no capacity
        // to bin-pack, so this stays a skip rather than a freshness verdict.
        continue;
      }
      if (freshness.hasGoneDark(true, heartbeat, observingSince, now)) {
        // A node that has stopped heartbeating is not merely a neutral candidate, it is an
        // attractive one: its last report is frozen at whatever capacity it had while alive, and
        // its assignments have just been released, so it looks like the emptiest machine in the
        // cluster. Placing here would re-place an instance onto a machine that is not answering --
        // and since that placement is never confirmed, release and re-place would trade forever
        // while the workload stays down.
        continue;
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisWorkload.contains(registration.nodeId()),
              store.getNodeTaints(registration.nodeId()),
              store.isNodeCordoned(registration.nodeId()),
              List.of(),
              tier2TenantsOf(heartbeat.get())));
    }
    return candidates;
  }

  /**
   * The tenant IDs of every {@code TIER_2} instance this node's latest heartbeat reports running --
   * the node-level tenant-isolation scoring signal {@link Scheduler#place} reads. Read straight off
   * each {@link InstanceObservation}, so it costs nothing beyond the heartbeat the candidate list
   * already reads for capacity -- no artifact resolve, unlike {@code ResidentInstances}.
   */
  private static Set<String> tier2TenantsOf(ObservedHeartbeat observed) {
    return observed.heartbeat().instances().stream()
        .filter(
            instance -> instance.isolationTier().stream().anyMatch(IsolationTier.TIER_2::equals))
        .map(InstanceObservation::tenantId)
        .flatMap(Optional::stream)
        .collect(Collectors.toUnmodifiableSet());
  }
}
