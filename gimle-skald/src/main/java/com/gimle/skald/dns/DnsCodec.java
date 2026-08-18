package com.gimle.skald.dns;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled encoder/decoder for the slice of RFC 1035's wire format Skald actually needs: a
 * one-question query (header + QNAME/QTYPE/QCLASS) in, a header-plus-question-plus-A-records
 * response out. No third-party DNS library -- the same "hand-roll the wire codec" posture {@code
 * FabricCodec} already takes for the fabric transport, applied here because this schema is just as
 * small and fixed.
 *
 * <p>Only the fields this server's own behavior depends on are modeled explicitly (query id,
 * opcode, the RD bit, and the one question); everything else in a real query (ANCOUNT/NSCOUNT/
 * ARCOUNT, any additional questions or records) is read past and discarded on decode, since a
 * standard resolver's own query never sets any of them and this server never needs to echo them
 * back verbatim.
 */
public final class DnsCodec {

  /** DNS header is always exactly 12 bytes (RFC 1035 §4.1.1). */
  private static final int HEADER_LENGTH = 12;

  /** A DNS label is length-prefixed by one byte, so it can never exceed 63 bytes (6 bits). */
  private static final int MAX_LABEL_LENGTH = 63;

  /** RFC 1035 §3.1's own ceiling on a full encoded domain name. */
  private static final int MAX_NAME_LENGTH = 255;

  /**
   * Short on purpose: cluster endpoints churn (deploys, rescheduling, scale events), and the poller
   * refreshing Skald's own cache already bounds real-world staleness far tighter than a
   * conventional multi-minute DNS TTL would -- a short TTL just tells a caching resolver in front
   * of Skald to check back at roughly that same cadence instead of holding a stale answer far
   * longer.
   */
  private static final int ANSWER_TTL_SECONDS = 5;

  public static final int TYPE_A = 1;
  public static final int TYPE_AAAA = 28;
  public static final int CLASS_IN = 1;
  public static final int OPCODE_QUERY = 0;

  public static final int RCODE_NOERROR = 0;
  public static final int RCODE_NXDOMAIN = 3;
  public static final int RCODE_NOTIMP = 4;

  private DnsCodec() {}

  /** One decoded query: the fields needed to resolve an answer and to build a matching response. */
  public record Query(int id, int opcode, boolean recursionDesired, Question question) {}

  /**
   * A question section: {@code QNAME} as its labels (no trailing empty label), {@code QTYPE}/{@code
   * QCLASS}.
   */
  public record Question(List<String> labels, int qtype, int qclass) {

    public Question {
      labels = List.copyOf(labels);
    }

    /** The dotted-label form, e.g. {@code "orders.svc.gimle.local"}. */
    public String name() {
      return String.join(".", labels);
    }
  }

  /**
   * Decodes the header and first question of a raw query datagram. Throws {@link
   * IllegalArgumentException} for anything too short or malformed to answer at all -- the caller is
   * expected to drop such a datagram rather than reply to it, matching a real resolver's own
   * response to a garbled packet.
   */
  public static Query decodeQuery(byte[] packet, int length) {
    if (length < HEADER_LENGTH) {
      throw new IllegalArgumentException(
          "DNS packet shorter than the 12-byte header: " + length + " byte(s)");
    }
    ByteBuffer buf = ByteBuffer.wrap(packet, 0, length);
    int id = buf.getShort() & 0xFFFF;
    int flags = buf.getShort() & 0xFFFF;
    int qr = (flags >>> 15) & 0x1;
    int opcode = (flags >>> 11) & 0xF;
    boolean recursionDesired = ((flags >>> 8) & 0x1) == 1;
    int qdCount = buf.getShort() & 0xFFFF;
    buf.getShort(); // ANCOUNT -- always 0 on an incoming query, not needed to build a reply
    buf.getShort(); // NSCOUNT -- ditto
    buf.getShort(); // ARCOUNT -- ditto (an OPT/EDNS0 pseudo-record here is simply ignored)
    if (qr != 0) {
      throw new IllegalArgumentException("expected a query (QR=0), got a response (QR=1)");
    }
    if (qdCount < 1) {
      throw new IllegalArgumentException("query carries no question (QDCOUNT=0)");
    }
    DecodedName name = decodeName(packet, buf.position(), length);
    buf.position(name.nextOffset());
    if (buf.remaining() < 4) {
      throw new IllegalArgumentException("truncated question: missing QTYPE/QCLASS");
    }
    int qtype = buf.getShort() & 0xFFFF;
    int qclass = buf.getShort() & 0xFFFF;
    // A resolver's own query always carries exactly one question in practice; any second question
    // or trailing record is left unparsed -- this server only ever answers the first.
    return new Query(id, opcode, recursionDesired, new Question(name.labels(), qtype, qclass));
  }

  /**
   * Encodes a response echoing {@code query}'s id/opcode/question, carrying {@code rcode} and one A
   * record per address in {@code answerAddresses} (each exactly 4 bytes). {@code AA} is always set
   * (this server is authoritative for the zone it answers), {@code RA} is always clear (it never
   * recurses to another resolver), and {@code TC} is always clear (a handful of A records never
   * approaches the 512-byte unextended UDP ceiling).
   */
  public static byte[] encodeResponse(Query query, int rcode, List<byte[]> answerAddresses) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeShort(query.id());
      int flags = 0;
      flags |= 0x1 << 15; // QR=1: this is a response
      flags |= (query.opcode() & 0xF) << 11;
      flags |= 0x1 << 10; // AA=1: authoritative for the svc.gimle.local zone
      flags |= (query.recursionDesired() ? 1 : 0) << 8; // RD echoed back, as a resolver expects
      flags |= (rcode & 0xF);
      out.writeShort(flags);
      out.writeShort(1); // QDCOUNT: the one question this response echoes
      out.writeShort(answerAddresses.size()); // ANCOUNT
      out.writeShort(0); // NSCOUNT
      out.writeShort(0); // ARCOUNT
      writeName(out, query.question().labels());
      out.writeShort(query.question().qtype());
      out.writeShort(query.question().qclass());
      for (byte[] address : answerAddresses) {
        if (address.length != 4) {
          throw new IllegalArgumentException(
              "an A record answer must carry exactly 4 address bytes, got " + address.length);
        }
        // Name-compression pointer back to the question's QNAME at offset 12 (right after the
        // fixed-size header) instead of repeating the labels -- every answer here echoes exactly
        // the name that was queried, so re-encoding it a second time would just be dead weight.
        out.writeByte(0xC0);
        out.writeByte(0x0C);
        out.writeShort(TYPE_A);
        out.writeShort(CLASS_IN);
        out.writeInt(ANSWER_TTL_SECONDS);
        out.writeShort(4);
        out.write(address);
      }
    } catch (IOException e) {
      // ByteArrayOutputStream never actually throws; this exists only because DataOutputStream's
      // write* methods declare IOException.
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static void writeName(DataOutputStream out, List<String> labels) throws IOException {
    for (String label : labels) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.writeByte(bytes.length);
      out.write(bytes);
    }
    out.writeByte(0);
  }

  private record DecodedName(List<String> labels, int nextOffset) {}

  private static DecodedName decodeName(byte[] packet, int offset, int length) {
    List<String> labels = new ArrayList<>();
    int pos = offset;
    int encodedLength = 0;
    while (true) {
      if (pos >= length) {
        throw new IllegalArgumentException("truncated DNS name");
      }
      int labelLength = packet[pos] & 0xFF;
      if ((labelLength & 0xC0) == 0xC0) {
        // Compression pointers are a response-encoding optimization for repeating an earlier
        // name; a query's own QNAME is the very first thing after the header, so a well-formed
        // resolver never needs one there. Reject rather than chase a pointer this decoder was
        // never asked to resolve.
        throw new IllegalArgumentException("compressed name pointers are not supported in a query");
      }
      pos++;
      if (labelLength == 0) {
        break;
      }
      if (labelLength > MAX_LABEL_LENGTH) {
        throw new IllegalArgumentException("DNS label exceeds 63 bytes: " + labelLength);
      }
      if (pos + labelLength > length) {
        throw new IllegalArgumentException("truncated DNS label");
      }
      encodedLength += labelLength + 1;
      if (encodedLength > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException("DNS name exceeds 255 bytes");
      }
      labels.add(new String(packet, pos, labelLength, StandardCharsets.US_ASCII));
      pos += labelLength;
    }
    return new DecodedName(List.copyOf(labels), pos);
  }
}
