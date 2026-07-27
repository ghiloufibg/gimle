package com.gimle.fabric.cluster;

/**
 * Optional application data riding on the same gossip piggyback channel as membership: the service
 * catalog reuses SWIM's own infection-style dissemination rather than building a second mechanism.
 * {@link GossipMember} treats the payload as opaque bytes -- SWIM itself stays domain-agnostic
 * about what rides along; {@code com.gimle.fabric.catalog.ServiceCatalog} is the one real
 * implementation today, encoding/decoding its own delta list.
 */
public interface PiggybackExtension {

  PiggybackExtension NONE =
      new PiggybackExtension() {
        @Override
        public byte[] currentPayload() {
          return new byte[0];
        }

        @Override
        public void onReceived(byte[] payload) {}
      };

  byte[] currentPayload();

  void onReceived(byte[] payload);
}
