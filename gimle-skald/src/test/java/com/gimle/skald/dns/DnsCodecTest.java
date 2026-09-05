package com.gimle.skald.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DnsCodecTest {

  @Test
  void decodes_a_standard_query_header_and_question() throws IOException {
    byte[] packet =
        buildQuery(0xABCD, "orders.svc.gimle.local", DnsCodec.TYPE_A, DnsCodec.OPCODE_QUERY, true);

    DnsCodec.Query query = DnsCodec.decodeQuery(packet, packet.length);

    assertEquals(0xABCD, query.id());
    assertEquals(DnsCodec.OPCODE_QUERY, query.opcode());
    assertTrue(query.recursionDesired());
    assertEquals(List.of("orders", "svc", "gimle", "local"), query.question().labels());
    assertEquals("orders.svc.gimle.local", query.question().name());
    assertEquals(DnsCodec.TYPE_A, query.question().qtype());
    assertEquals(DnsCodec.CLASS_IN, query.question().qclass());
  }

  @Test
  void encodes_a_noerror_response_carrying_one_answer() throws IOException {
    byte[] packet =
        buildQuery(1, "orders.svc.gimle.local", DnsCodec.TYPE_A, DnsCodec.OPCODE_QUERY, true);
    DnsCodec.Query query = DnsCodec.decodeQuery(packet, packet.length);

    byte[] response =
        DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of(new byte[] {10, 0, 0, 5}));

    // Re-decoding the response's own header as if it were a query is not valid (QR=1 there), so
    // this test reads the fixed header fields directly instead of round-tripping through decode.
    assertEquals(1, unsignedShort(response, 0)); // echoes the query id
    int flags = unsignedShort(response, 2);
    assertEquals(1, (flags >>> 15) & 0x1); // QR: response
    assertEquals(1, (flags >>> 10) & 0x1); // AA: authoritative
    assertEquals(DnsCodec.RCODE_NOERROR, flags & 0xF);
    assertEquals(1, unsignedShort(response, 4)); // QDCOUNT
    assertEquals(1, unsignedShort(response, 6)); // ANCOUNT

    // Answer RDATA sits after: header(12) + QNAME + QTYPE/QCLASS(4) + name-ptr(2) + TYPE(2) +
    // CLASS(2) + TTL(4) + RDLENGTH(2).
    int questionEnd = 12 + encodedNameLength("orders.svc.gimle.local") + 4;
    int rdataOffset = questionEnd + 2 + 2 + 2 + 4 + 2;
    byte[] rdata = new byte[4];
    System.arraycopy(response, rdataOffset, rdata, 0, 4);
    assertArrayEquals(new byte[] {10, 0, 0, 5}, rdata);
  }

  private static int encodedNameLength(String dotted) {
    int length = 1; // terminating zero-length label
    for (String label : dotted.split("\\.")) {
      length += 1 + label.length();
    }
    return length;
  }

  @Test
  void rejects_a_packet_shorter_than_the_header() {
    byte[] tooShort = new byte[] {1, 2, 3};
    assertThrows(
        IllegalArgumentException.class, () -> DnsCodec.decodeQuery(tooShort, tooShort.length));
  }

  @Test
  void rejects_a_response_packet_with_qr_already_set() throws IOException {
    byte[] packet =
        buildQuery(1, "orders.svc.gimle.local", DnsCodec.TYPE_A, DnsCodec.OPCODE_QUERY, true);
    packet[2] |= (byte) 0x80; // set QR
    assertThrows(IllegalArgumentException.class, () -> DnsCodec.decodeQuery(packet, packet.length));
  }

  @Test
  void rejects_a_query_with_no_question() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeShort(1);
    out.writeShort(0);
    out.writeShort(0); // QDCOUNT = 0
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(0);
    byte[] packet = buffer.toByteArray();
    assertThrows(IllegalArgumentException.class, () -> DnsCodec.decodeQuery(packet, packet.length));
  }

  @Test
  void encode_response_rejects_an_answer_address_that_is_not_four_bytes() throws IOException {
    byte[] packet =
        buildQuery(1, "orders.svc.gimle.local", DnsCodec.TYPE_A, DnsCodec.OPCODE_QUERY, true);
    DnsCodec.Query query = DnsCodec.decodeQuery(packet, packet.length);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of(new byte[] {1, 2, 3})));
  }

  @Test
  void truncated_response_sets_the_tc_flag_and_a_full_one_leaves_it_clear() throws IOException {
    byte[] raw = buildQuery(0x77, "orders.svc.gimle.local", DnsCodec.TYPE_A, 0, true);
    DnsCodec.Query query = DnsCodec.decodeQuery(raw, raw.length);

    byte[] truncated = DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of(), true);
    byte[] full =
        DnsCodec.encodeResponse(
            query,
            DnsCodec.RCODE_NOERROR,
            List.of(DnsCodec.Answer.a(new byte[] {10, 0, 0, 5})),
            false);

    assertEquals(1, (unsignedShort(truncated, 2) >>> 9) & 0x1);
    // Encoding directly with an empty answer list is still legal and still ANCOUNT 0; what the
    // UDP path does with a real over-ceiling response is truncateForUdp's own concern.
    assertEquals(0, unsignedShort(truncated, 6));
    assertEquals(0, (unsignedShort(full, 2) >>> 9) & 0x1);
  }

  @Test
  void a_response_over_the_udp_ceiling_keeps_every_answer_that_still_fits() throws IOException {
    byte[] raw = buildQuery(0x42, "orders.svc.gimle.local", DnsCodec.TYPE_A, 0, true);
    DnsCodec.Query query = DnsCodec.decodeQuery(raw, raw.length);
    List<DnsCodec.Answer> answers = new ArrayList<>();
    for (int i = 1; i <= 40; i++) {
      answers.add(DnsCodec.Answer.a(new byte[] {10, 0, 0, (byte) i}));
    }
    byte[] full = DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, answers, false);
    assertTrue(full.length > 512, "fixture must genuinely overrun the ceiling: " + full.length);

    byte[] truncated = DnsCodec.truncateForUdp(full, 512);

    assertTrue(truncated.length <= 512);
    assertEquals(1, (unsignedShort(truncated, 2) >>> 9) & 0x1, "TC must be set");
    int kept = unsignedShort(truncated, 6);
    // The whole point of the fix: a truncated datagram carries what it had room for, rather than
    // forcing a TCP round trip for information it could have delivered.
    assertTrue(kept > 0 && kept < answers.size(), "expected a partial fill, got " + kept);
    // Every kept record is whole -- the prefix is byte-identical to the untruncated response.
    for (int i = 12; i < truncated.length; i++) {
      if (i == 2 || i == 3 || i == 6 || i == 7) {
        continue; // flags and ANCOUNT are the two fields truncation rewrites
      }
      assertEquals(full[i], truncated[i], "byte " + i + " diverged from the full response");
    }
  }

  @Test
  void a_response_already_within_the_ceiling_is_returned_untouched() throws IOException {
    byte[] raw = buildQuery(0x43, "orders.svc.gimle.local", DnsCodec.TYPE_A, 0, true);
    DnsCodec.Query query = DnsCodec.decodeQuery(raw, raw.length);
    byte[] full =
        DnsCodec.encodeResponse(
            query,
            DnsCodec.RCODE_NOERROR,
            List.of(DnsCodec.Answer.a(new byte[] {10, 0, 0, 5})),
            false);

    assertSame(full, DnsCodec.truncateForUdp(full, 512));
  }

  private static byte[] buildQuery(
      int id, String name, int qtype, int opcode, boolean recursionDesired) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeShort(id);
    int flags = (opcode & 0xF) << 11;
    flags |= (recursionDesired ? 1 : 0) << 8;
    out.writeShort(flags);
    out.writeShort(1); // QDCOUNT
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(0);
    for (String label : name.split("\\.")) {
      byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      out.writeByte(bytes.length);
      out.write(bytes);
    }
    out.writeByte(0);
    out.writeShort(qtype);
    out.writeShort(DnsCodec.CLASS_IN);
    return buffer.toByteArray();
  }

  private static int unsignedShort(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
  }
}
