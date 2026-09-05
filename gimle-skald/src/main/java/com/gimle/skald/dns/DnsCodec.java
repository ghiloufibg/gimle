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
  public static final int TYPE_CNAME = 5;
  public static final int TYPE_AAAA = 28;
  public static final int TYPE_SRV = 33;
  public static final int CLASS_IN = 1;
  public static final int OPCODE_QUERY = 0;

  public static final int RCODE_NOERROR = 0;
  public static final int RCODE_SERVFAIL = 2;
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
   * One answer record: its RR {@code type} and pre-encoded {@code rdata}. The owner name is always
   * the question's own QNAME (every record this server answers is for exactly the name queried), so
   * it isn't modeled here -- {@link #encodeResponse} emits a compression pointer back to the
   * question for each record.
   */
  public record Answer(int type, byte[] rdata) {

    /** An {@code A} record answer -- {@code address} is exactly 4 IPv4 bytes. */
    public static Answer a(byte[] address) {
      if (address.length != 4) {
        throw new IllegalArgumentException(
            "an A record answer must carry exactly 4 address bytes, got " + address.length);
      }
      return new Answer(TYPE_A, address.clone());
    }

    /**
     * A {@code CNAME} record answer (RFC 1035 §3.3.1): the canonical name the queried name is an
     * alias for, encoded uncompressed -- what an ExternalName Service answers with, since the
     * external host it points at is a name outside this server's zone, not an address it holds.
     */
    public static Answer cname(List<String> canonicalLabels) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(buffer);
      try {
        writeName(out, canonicalLabels);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return new Answer(TYPE_CNAME, buffer.toByteArray());
    }

    /**
     * An {@code SRV} record answer (RFC 2782): priority/weight/port plus the target name, encoded
     * uncompressed as that RFC requires of an SRV RDATA's own name.
     */
    public static Answer srv(int priority, int weight, int port, List<String> targetLabels) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(buffer);
      try {
        out.writeShort(priority);
        out.writeShort(weight);
        out.writeShort(port);
        writeName(out, targetLabels);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return new Answer(TYPE_SRV, buffer.toByteArray());
    }
  }

  /**
   * A-record convenience over {@link #encodeResponse(Query, int, List, boolean)} -- one {@code A}
   * record per 4-byte address, {@code TC} clear.
   */
  public static byte[] encodeResponse(Query query, int rcode, List<byte[]> answerAddresses) {
    return encodeResponse(query, rcode, answerAddresses.stream().map(Answer::a).toList(), false);
  }

  /**
   * Encodes a response echoing {@code query}'s id/opcode/question, carrying {@code rcode} and the
   * given answer records. {@code AA} is always set (this server is authoritative for the zone it
   * answers), {@code RA} is always clear (it never recurses to another resolver). The {@code
   * truncated} variant exists for the UDP path only: it sets {@code TC=1}, telling the resolver to
   * retry the identical query over TCP where the full response fits (RFC 1035 §4.2.1's own
   * contract). Callers reaching the UDP ceiling should build the full response and hand it to
   * {@link #truncateForUdp} rather than re-encoding with no answers, so the datagram still carries
   * whatever complete records fit.
   */
  public static byte[] encodeResponse(
      Query query, int rcode, List<Answer> answers, boolean truncated) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeShort(query.id());
      int flags = 0;
      flags |= 0x1 << 15; // QR=1: this is a response
      flags |= (query.opcode() & 0xF) << 11;
      flags |= 0x1 << 10; // AA=1: authoritative for the svc.gimle.local zone
      flags |= (truncated ? 1 : 0) << 9; // TC: retry over TCP for the full answer
      flags |= (query.recursionDesired() ? 1 : 0) << 8; // RD echoed back, as a resolver expects
      flags |= (rcode & 0xF);
      out.writeShort(flags);
      out.writeShort(1); // QDCOUNT: the one question this response echoes
      out.writeShort(answers.size()); // ANCOUNT
      out.writeShort(0); // NSCOUNT
      out.writeShort(0); // ARCOUNT
      writeName(out, query.question().labels());
      out.writeShort(query.question().qtype());
      out.writeShort(query.question().qclass());
      for (Answer answer : answers) {
        // Name-compression pointer back to the question's QNAME at offset 12 (right after the
        // fixed-size header) instead of repeating the labels -- every answer here echoes exactly
        // the name that was queried, so re-encoding it a second time would just be dead weight.
        out.writeByte(0xC0);
        out.writeByte(0x0C);
        out.writeShort(answer.type());
        out.writeShort(CLASS_IN);
        out.writeInt(ANSWER_TTL_SECONDS);
        out.writeShort(answer.rdata().length);
        out.write(answer.rdata());
      }
    } catch (IOException e) {
      // ByteArrayOutputStream never actually throws; this exists only because DataOutputStream's
      // write* methods declare IOException.
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  /**
   * Trims an already-encoded response down to {@code maxBytes} by dropping whole answer records
   * from the end, then sets {@code TC=1} and rewrites {@code ANCOUNT} to match what is left.
   *
   * <p>Keeping the records that do fit is what RFC 1035 §4.1.1 describes and what a conventional
   * resolver expects: a truncated message is still a usable partial answer, and a caller that can
   * act on any one of several equivalent endpoints never has to make the TCP round trip at all. An
   * empty truncated response is correct but strictly worse -- it forces every caller to retry for
   * information the datagram had room to carry.
   *
   * <p>Operates on the encoded bytes rather than the answer list because every branch that builds a
   * response here encodes as it goes; the layout it walks is exactly the one {@link
   * #encodeResponse} writes. A response whose header and question alone exceed {@code maxBytes} is
   * returned with no answers at all -- the smallest valid reply, even if it still overruns.
   */
  public static byte[] truncateForUdp(byte[] response, int maxBytes) {
    if (response.length <= maxBytes) {
      return response;
    }
    ByteBuffer in = ByteBuffer.wrap(response);
    int answerCount = Short.toUnsignedInt(in.getShort(6));
    int cursor = skipQuestion(response);
    int kept = 0;
    for (int i = 0; i < answerCount; i++) {
      int next = skipAnswer(response, cursor);
      if (next < 0 || next > maxBytes) {
        break;
      }
      cursor = next;
      kept++;
    }
    byte[] truncated = new byte[cursor];
    System.arraycopy(response, 0, truncated, 0, cursor);
    truncated[2] |= 0x02; // TC lives in the high byte of the 16-bit flags field, at bit 9
    truncated[6] = (byte) (kept >>> 8);
    truncated[7] = (byte) kept;
    return truncated;
  }

  /** Offset just past the single question this codec always writes. */
  private static int skipQuestion(byte[] response) {
    int afterName = skipName(response, HEADER_LENGTH);
    // A malformed header/question can't come from this codec's own encoder; falling back to the
    // bare header keeps the result a valid (if answerless) response rather than a torn one.
    return afterName < 0 || afterName + 4 > response.length ? HEADER_LENGTH : afterName + 4;
  }

  /**
   * Offset just past the answer record starting at {@code cursor}, or {@code -1} if the record runs
   * past the end of {@code response}.
   */
  private static int skipAnswer(byte[] response, int cursor) {
    int afterName = skipName(response, cursor);
    if (afterName < 0) {
      return -1;
    }
    int rdLengthAt = afterName + 2 + 2 + 4; // TYPE, CLASS, TTL
    if (rdLengthAt + 2 > response.length) {
      return -1;
    }
    int rdLength = Short.toUnsignedInt(ByteBuffer.wrap(response).getShort(rdLengthAt));
    int end = rdLengthAt + 2 + rdLength;
    return end <= response.length ? end : -1;
  }

  /**
   * Offset just past the name at {@code cursor}: two bytes for a compression pointer (what every
   * answer {@link #encodeResponse} writes carries), otherwise the length-prefixed labels plus their
   * terminating zero. {@code -1} if it runs off the end.
   */
  private static int skipName(byte[] response, int cursor) {
    if (cursor >= response.length) {
      return -1;
    }
    if ((response[cursor] & 0xC0) == 0xC0) {
      return cursor + 2 <= response.length ? cursor + 2 : -1;
    }
    int at = cursor;
    while (at < response.length && response[at] != 0) {
      at += Byte.toUnsignedInt(response[at]) + 1;
    }
    return at < response.length ? at + 1 : -1;
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
