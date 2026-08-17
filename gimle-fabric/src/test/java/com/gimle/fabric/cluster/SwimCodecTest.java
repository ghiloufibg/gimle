package com.gimle.fabric.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleCodecException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SwimCodecTest {

  private static final MemberId MEMBER_A =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7001));
  private static final MemberId MEMBER_B =
      new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7002));

  private static List<MemberState> piggyback() {
    return List.of(
        new MemberState(MEMBER_A, MemberStatus.ALIVE, 1L),
        new MemberState(MEMBER_B, MemberStatus.SUSPECT, 3L));
  }

  static Stream<SwimMessage> allMessageVariants() {
    byte[] catalogPayload = new byte[] {1, 2, 3, 4};
    return Stream.of(
        new SwimMessage.Ping(1L, piggyback(), catalogPayload),
        new SwimMessage.PingReq(2L, MEMBER_A, piggyback(), catalogPayload),
        new SwimMessage.Ack(3L, MEMBER_B, piggyback(), catalogPayload),
        new SwimMessage.IndirectAck(4L, MEMBER_A, piggyback(), catalogPayload),
        new SwimMessage.SyncRequest(5L, piggyback(), catalogPayload),
        new SwimMessage.SyncResponse(6L, piggyback(), catalogPayload));
  }

  @ParameterizedTest
  @MethodSource("allMessageVariants")
  void round_trips_through_a_datagram(SwimMessage original) {
    byte[] datagram = SwimCodec.encode(original);
    SwimMessage decoded = SwimCodec.decode(datagram, datagram.length);
    assertEquals(original.seq(), decoded.seq());
    assertEquals(original.piggyback(), decoded.piggyback());
    assertArrayEquals(original.catalogPayload(), decoded.catalogPayload());
  }

  @Test
  void rejects_an_oversized_catalog_payload_length_before_allocating() throws IOException {
    byte[] datagram = pingDatagramWithForgedCatalogLength(Integer.MAX_VALUE - 8);
    assertThrows(GimleCodecException.class, () -> SwimCodec.decode(datagram, datagram.length));
  }

  @Test
  void rejects_a_negative_catalog_payload_length_before_allocating() throws IOException {
    byte[] datagram = pingDatagramWithForgedCatalogLength(-1);
    assertThrows(GimleCodecException.class, () -> SwimCodec.decode(datagram, datagram.length));
  }

  @Test
  void a_forged_huge_piggyback_count_fails_cleanly_instead_of_preallocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(1); // version
    out.writeByte(0); // TAG_PING
    out.writeLong(1L); // seq
    out.writeInt(Integer.MAX_VALUE - 8); // forged piggyback count
    // No member entries follow -- the datagram ends here, so a pre-sized ArrayList would have
    // allocated an ~8GB backing array before ever discovering that.
    byte[] datagram = buffer.toByteArray();

    assertThrows(UncheckedIOException.class, () -> SwimCodec.decode(datagram, datagram.length));
  }

  @Test
  void rejects_an_unrecognized_version_before_decoding_the_tag() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(99); // an unrecognized future version
    // Deliberately garbage after the version byte -- the version check must reject before any of
    // this is interpreted as a tag/fields.
    out.writeByte(0);
    out.writeLong(0L);
    byte[] datagram = buffer.toByteArray();

    GimleCodecException thrown =
        assertThrows(GimleCodecException.class, () -> SwimCodec.decode(datagram, datagram.length));
    assertTrue(
        thrown.getMessage().contains("99") && thrown.getMessage().contains("1"),
        "expected the message to name both the declared (99) and max supported (1) versions, got: "
            + thrown.getMessage());
  }

  private static byte[] pingDatagramWithForgedCatalogLength(int forgedLength) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(1); // version
    out.writeByte(0); // TAG_PING
    out.writeLong(1L); // seq
    out.writeInt(0); // piggyback count = 0
    out.writeInt(forgedLength); // forged catalog payload length
    return buffer.toByteArray();
  }
}
