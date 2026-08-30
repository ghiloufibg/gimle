package com.gimle.fabric.cluster;

/**
 * Optional application data riding on the same gossip piggyback channel as membership: the service
 * catalog reuses SWIM's own infection-style dissemination rather than building a second mechanism.
 * {@link GossipMember} treats both payloads as opaque bytes -- SWIM itself stays domain-agnostic
 * about what rides along; {@code com.gimle.fabric.catalog.ServiceCatalog} is the one real
 * implementation today, encoding/decoding its own delta list.
 *
 * <p>Two payload shapes mirror the two sync channels {@link GossipMember} itself already runs for
 * membership: {@link #currentPayload()} is the cheap, bounded set riding every {@code Ping}/{@code
 * Ack}/{@code PingReq}/{@code IndirectAck}, and {@link #currentFullStatePayload()} is the real
 * anti-entropy backstop riding only {@code SyncRequest}/{@code SyncResponse} -- a genuine snapshot
 * of everything this side currently knows (paginated the same way {@link
 * GossipMember#currentFullState} paginates membership), not the same bounded set reused verbatim. A
 * node that misses the bounded payload's narrow window for a given entry -- a partition, packet
 * loss, or enough concurrent unrelated churn -- would otherwise never learn of it at all; anti-
 * entropy exists precisely to close that gap the same way it already does for membership.
 */
public interface PiggybackExtension {

  PiggybackExtension NONE =
      new PiggybackExtension() {
        @Override
        public byte[] currentPayload() {
          return new byte[0];
        }

        @Override
        public byte[] currentFullStatePayload() {
          return new byte[0];
        }

        @Override
        public void onReceived(byte[] payload) {}
      };

  byte[] currentPayload();

  byte[] currentFullStatePayload();

  void onReceived(byte[] payload);
}
