package com.gimle.mimir.raft;

/**
 * The empty entry a newly-elected leader appends at its own term before anything else. Raft's
 * commit rule only ever lets a leader commit an entry from its own current term (and everything
 * before it, transitively) -- so without one entry of its own, a fresh leader over a quiet cluster
 * could never advance its commit index past what it inherited, leaving every entry appended by its
 * predecessors committed-but-unapplied on this node until the next client write happened to arrive.
 * The state machine holds no durable state of its own between restarts (recovery is snapshot plus
 * committed-log replay), so that catch-up must not wait on a write that may never come: this entry
 * is what forces it immediately.
 */
public record Noop() implements RaftLogPayload {}
