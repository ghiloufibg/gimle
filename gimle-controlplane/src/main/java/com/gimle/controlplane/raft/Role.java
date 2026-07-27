package com.gimle.controlplane.raft;

/** A Raft node's current role (design §2.2). */
public enum Role {
  FOLLOWER,
  CANDIDATE,
  LEADER
}
