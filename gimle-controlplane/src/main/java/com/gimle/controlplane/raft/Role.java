package com.gimle.controlplane.raft;

/** A Raft node's current role. */
public enum Role {
  FOLLOWER,
  CANDIDATE,
  LEADER
}
