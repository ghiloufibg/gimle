package com.gimle.fabric.cluster;

/**
 * A member's status in another member's local view -- never a global truth, per SWIM's whole
 * premise. Ordered by severity ({@link #severity()}) so a merge can decide whether an incoming
 * claim at the same incarnation should override the locally-held one (escalate only, never silently
 * downgrade at equal incarnation -- that would undermine refutation).
 */
public enum MemberStatus {
  ALIVE,
  SUSPECT,
  DEAD;

  public int severity() {
    return ordinal();
  }
}
