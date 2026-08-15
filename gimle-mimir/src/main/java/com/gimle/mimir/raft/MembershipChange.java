package com.gimle.mimir.raft;

import java.util.Map;
import java.util.Set;

/**
 * A complete replacement of this cluster's peer configuration, not a delta -- etcd-style: only one
 * {@link MembershipChange} is ever in flight at a time (enforced by {@link RaftNode#addServer}/
 * {@link RaftNode#removeServer} rejecting a new one while an earlier one they proposed is still
 * uncommitted), each one entirely replacing {@code C_old} with {@code C_new} -- deliberately not
 * full joint consensus's {@code C_old,new} overlap state, which one-server-at-a-time changes make
 * unnecessary: any old-majority and new-majority pair for a single-server change is guaranteed to
 * overlap, so there's no window where both configurations could elect different leaders.
 *
 * <p>{@code peers} is the full membership <em>including</em> whoever proposed it -- unlike a {@link
 * RaftNode}'s own live peer set, which never lists the node it belongs to, this record is the same
 * value replicated verbatim to every node in the cluster, so it has to carry every member's address
 * for it to be reconstructible by each of them: a receiving node recovers its own correct peer set
 * by dropping only its own id from this map. Without the proposer's own address folded in here,
 * every *other* node would silently lose the proposer as a peer the moment this replicated -- a
 * node never lists itself in its own peer set, the map this record is built from.
 *
 * <p>{@code learners} names the subset of {@code peers} that are currently non-voting: added via
 * {@link RaftNode#addServer} but not yet caught up, promoted automatically once they are. A learner
 * still receives normal log replication -- it just doesn't count toward election/commit majority
 * arithmetic and never campaigns for election itself. Every learner id is also a peer id; there is
 * no such thing as a learner that isn't a member.
 */
public record MembershipChange(Map<String, PeerAddress> peers, Set<String> learners)
    implements RaftLogPayload {

  public MembershipChange {
    peers = Map.copyOf(peers);
    learners = Set.copyOf(learners);
    if (!peers.keySet().containsAll(learners)) {
      throw new IllegalArgumentException(
          "every learner must also be a peer, learners=" + learners + " peers=" + peers.keySet());
    }
  }
}
