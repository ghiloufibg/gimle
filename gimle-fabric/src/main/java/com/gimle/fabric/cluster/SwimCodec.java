package com.gimle.fabric.cluster;

import com.gimle.core.exception.GimleCodecException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes a {@link SwimMessage} to a single UDP datagram payload: a one-byte wire-protocol
 * version, a one-byte message-type tag, the type's own fields, then the membership piggyback list,
 * then the opaque catalog piggyback payload. Package-private -- an implementation detail of {@link
 * GossipMember}, not a public wire contract other modules touch directly.
 *
 * <p>The version byte is the literal first byte of the datagram (there's no length prefix at this
 * layer to put it after -- the datagram boundary is the frame boundary) and is checked before any
 * version-specific field is decoded, matching {@code FabricCodec}'s same "reject, don't misdecode"
 * posture.
 */
final class SwimCodec {

  /** The only wire-protocol version any writer produces today; bump this when the shape changes. */
  private static final int CURRENT_VERSION = 1;

  private static final byte TAG_PING = 0;
  private static final byte TAG_PING_REQ = 1;
  private static final byte TAG_ACK = 2;
  private static final byte TAG_INDIRECT_ACK = 3;

  /**
   * Max UDP payload size -- {@link GossipMember}'s receive buffer is itself capped at 65535 bytes,
   * so this is a provably tight bound (not just a generous guess) for any length/count prefix
   * decoded from a single datagram.
   */
  private static final int MAX_DATAGRAM_LENGTH = 65535;

  private SwimCodec() {}

  static byte[] encode(SwimMessage message) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeByte(CURRENT_VERSION);
      switch (message) {
        case SwimMessage.Ping m -> {
          out.writeByte(TAG_PING);
          out.writeLong(m.seq());
          writePiggyback(out, m.piggyback());
          writeCatalogPayload(out, m.catalogPayload());
        }
        case SwimMessage.PingReq m -> {
          out.writeByte(TAG_PING_REQ);
          out.writeLong(m.seq());
          writeMemberId(out, m.target());
          writePiggyback(out, m.piggyback());
          writeCatalogPayload(out, m.catalogPayload());
        }
        case SwimMessage.Ack m -> {
          out.writeByte(TAG_ACK);
          out.writeLong(m.seq());
          writeMemberId(out, m.from());
          writePiggyback(out, m.piggyback());
          writeCatalogPayload(out, m.catalogPayload());
        }
        case SwimMessage.IndirectAck m -> {
          out.writeByte(TAG_INDIRECT_ACK);
          out.writeLong(m.seq());
          writeMemberId(out, m.originalTarget());
          writePiggyback(out, m.piggyback());
          writeCatalogPayload(out, m.catalogPayload());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  static SwimMessage decode(byte[] datagram, int length) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(datagram, 0, length));
      int version = in.readByte();
      GimleCodecException.checkVersion(version, CURRENT_VERSION);
      byte tag = in.readByte();
      long seq = in.readLong();
      return switch (tag) {
        case TAG_PING -> {
          List<MemberState> piggyback = readPiggyback(in);
          yield new SwimMessage.Ping(seq, piggyback, readCatalogPayload(in));
        }
        case TAG_PING_REQ -> {
          MemberId target = readMemberId(in);
          List<MemberState> piggyback = readPiggyback(in);
          yield new SwimMessage.PingReq(seq, target, piggyback, readCatalogPayload(in));
        }
        case TAG_ACK -> {
          MemberId from = readMemberId(in);
          List<MemberState> piggyback = readPiggyback(in);
          yield new SwimMessage.Ack(seq, from, piggyback, readCatalogPayload(in));
        }
        case TAG_INDIRECT_ACK -> {
          MemberId originalTarget = readMemberId(in);
          List<MemberState> piggyback = readPiggyback(in);
          yield new SwimMessage.IndirectAck(seq, originalTarget, piggyback, readCatalogPayload(in));
        }
        default -> throw new IllegalArgumentException("unknown SWIM message tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeMemberId(DataOutputStream out, MemberId id) throws IOException {
    out.writeUTF(id.nodeId());
    out.writeUTF(id.gossipAddress().getHostString());
    out.writeInt(id.gossipAddress().getPort());
  }

  private static MemberId readMemberId(DataInputStream in) throws IOException {
    String nodeId = in.readUTF();
    String host = in.readUTF();
    int port = in.readInt();
    return new MemberId(nodeId, new InetSocketAddress(host, port));
  }

  private static void writePiggyback(DataOutputStream out, List<MemberState> piggyback)
      throws IOException {
    out.writeInt(piggyback.size());
    for (MemberState state : piggyback) {
      writeMemberId(out, state.id());
      out.writeByte(state.status().ordinal());
      out.writeLong(state.incarnation());
    }
  }

  private static List<MemberState> readPiggyback(DataInputStream in) throws IOException {
    int count = in.readInt();
    List<MemberState> piggyback = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      MemberId id = readMemberId(in);
      MemberStatus status = MemberStatus.values()[in.readByte()];
      long incarnation = in.readLong();
      piggyback.add(new MemberState(id, status, incarnation));
    }
    return piggyback;
  }

  private static void writeCatalogPayload(DataOutputStream out, byte[] payload) throws IOException {
    out.writeInt(payload.length);
    out.write(payload);
  }

  private static byte[] readCatalogPayload(DataInputStream in) throws IOException {
    int length = in.readInt();
    GimleCodecException.checkFrameLength(length, MAX_DATAGRAM_LENGTH);
    byte[] payload = new byte[length];
    in.readFully(payload);
    return payload;
  }
}
