package com.gimle.fabric.cluster;

/**
 * One member's status as of one incarnation number, the unit of SWIM's infection-style
 * dissemination and the piggyback slot the service catalog (Phase 4 §5) also rides on. {@code
 * incarnation} is bumped only by the member itself, to refute a suspicion of it -- everyone else's
 * knowledge of a member only ever moves forward in incarnation, never back.
 */
public record MemberState(MemberId id, MemberStatus status, long incarnation) {

  public MemberState {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
  }
}
