package com.gimle.mimir.raft;

/**
 * What a {@link LogEntry} carries: a {@link StateMutation} destined for {@code StateStore} once
 * committed; a {@link MembershipChange} to this cluster's own peer configuration -- which, uniquely
 * among everything replicated through this log, takes effect as soon as it is *appended* (leader or
 * follower), not only once committed (that "effective on append" rule is the one genuinely new
 * piece of Raft mechanics single-server membership changes require, regardless of whether joint
 * consensus is used -- see {@link RaftNode#addServer}/{@link RaftNode#removeServer} for the
 * etcd-style, one-change-at-a-time reduction this codebase ships instead of full joint consensus);
 * or a {@link Noop}, the empty entry a fresh leader appends to force its commit index forward over
 * its predecessors' entries.
 */
public sealed interface RaftLogPayload permits StateMutation, MembershipChange, Noop {}
