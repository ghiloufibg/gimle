package com.gimle.fabric.cluster;

import java.util.List;

/**
 * The four fixed shapes of SWIM's own message set, hand-rolled by {@code SwimCodec} rather than a
 * general-purpose serialization framework, since the schema is small and fully known up front.
 * Neither {@code Ping} nor {@code PingReq} names their sender: a reply always goes back to the UDP
 * packet's source address (the same "reply-to-sender" trick a stateless protocol like this needs no
 * address book for), and every outgoing message's {@code piggyback} always includes the sender's
 * own current {@link MemberState} so a brand-new member becomes known to whoever it first contacts
 * without a dedicated "from" field on every message shape.
 *
 * <p>{@code catalogPayload} is the opaque {@link PiggybackExtension} slot the service catalog rides
 * on: the catalog is disseminated over the same gossip piggyback channel as membership rather than
 * a second mechanism, made concrete as one more additive field alongside membership's own {@code
 * piggyback} list.
 */
public sealed interface SwimMessage {

  long seq();

  List<MemberState> piggyback();

  byte[] catalogPayload();

  /** A direct liveness probe. */
  record Ping(long seq, List<MemberState> piggyback, byte[] catalogPayload)
      implements SwimMessage {}

  /**
   * "Please probe {@code target} on my behalf" -- sent to {@code indirectFanout} random relays
   * after a direct {@link Ping} times out.
   */
  record PingReq(long seq, MemberId target, List<MemberState> piggyback, byte[] catalogPayload)
      implements SwimMessage {}

  /**
   * A direct reply to whichever {@link Ping} carried this {@code seq}, sent to that ping's source
   * address; {@code from} is the acking member's own id.
   */
  record Ack(long seq, MemberId from, List<MemberState> piggyback, byte[] catalogPayload)
      implements SwimMessage {}

  /**
   * A relay's report back to the original requester that {@code originalTarget} answered its
   * relayed probe; {@code seq} echoes the requester's own {@link PingReq#seq} so the requester can
   * resolve the pending indirect probe it's tracking.
   */
  record IndirectAck(
      long seq, MemberId originalTarget, List<MemberState> piggyback, byte[] catalogPayload)
      implements SwimMessage {}
}
