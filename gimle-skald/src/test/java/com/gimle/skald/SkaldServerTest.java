package com.gimle.skald;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.time.TestClock;
import com.gimle.skald.directory.CachingServiceDirectory;
import com.gimle.skald.directory.HostPort;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives a real bound {@link SkaldServer} over a loopback UDP socket with hand-crafted RFC 1035
 * query bytes, the same shape a real resolver's own query takes, and inspects the raw response
 * bytes directly -- proving the wire format end to end rather than only exercising {@link
 * com.gimle.skald.dns.DnsCodec} in isolation.
 */
final class SkaldServerTest {

  private CachingServiceDirectory directory;
  private SkaldServer server;
  private DatagramSocket clientSocket;

  @BeforeEach
  void setUp() throws IOException {
    directory = new CachingServiceDirectory();
    server = new SkaldServer(directory, 0);
    clientSocket = new DatagramSocket();
    clientSocket.setSoTimeout(5_000);
  }

  @AfterEach
  void tearDown() {
    clientSocket.close();
    server.close();
  }

  @Test
  void answers_a_tenant_scoped_hit_with_one_a_record() throws IOException {
    directory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));

    byte[] response = query(0x1234, "orders.acme.svc.gimle.local", 1);

    assertEquals(0x1234, unsignedShort(response, 0)); // echoes the query id
    int flags = unsignedShort(response, 2);
    assertEquals(1, (flags >>> 15) & 0x1); // QR: response
    assertEquals(1, (flags >>> 10) & 0x1); // AA: authoritative
    assertEquals(0, flags & 0xF); // RCODE: NOERROR
    assertEquals(1, unsignedShort(response, 4)); // QDCOUNT
    assertEquals(1, unsignedShort(response, 6)); // ANCOUNT
    assertArrayEquals(
        new byte[] {10, 0, 0, 5}, answerRdata(response, "orders.acme.svc.gimle.local"));
  }

  @Test
  void an_external_name_service_answers_a_cname_to_its_external_host() throws IOException {
    directory.replaceAll(Map.of("billing.acme", List.of(new HostPort("billing.example.com", 443))));

    byte[] response = query(0x21, "billing.acme.svc.gimle.local", 1);

    int flags = unsignedShort(response, 2);
    assertEquals(0, flags & 0xF); // RCODE: NOERROR -- the name exists, as an alias
    assertEquals(1, unsignedShort(response, 6)); // ANCOUNT: the one CNAME
    assertEquals(
        "billing.example.com",
        cnameTargetName(answerRdatas(response, "billing.acme.svc.gimle.local").get(0)));
  }

  @Test
  void an_srv_query_for_an_external_name_service_targets_the_external_host_itself()
      throws IOException {
    directory.replaceAll(
        Map.of("billing.acme", List.of(new HostPort("billing.example.com", 8443))));

    byte[] response = query(0x22, "billing.acme.svc.gimle.local", 33);

    List<byte[]> rdatas = answerRdatas(response, "billing.acme.svc.gimle.local");
    assertEquals(1, rdatas.size());
    assertEquals(8443, unsignedShort(rdatas.get(0), 4)); // SRV port
    assertEquals("billing.example.com", srvTargetName(rdatas.get(0)));
  }

  @Test
  void an_a_query_answers_every_endpoint_address_at_once() throws IOException {
    directory.replaceAll(
        Map.of("orders", List.of(new HostPort("10.0.0.5", 8080), new HostPort("10.0.0.6", 8080))));

    byte[] response = query(0x1, "orders.svc.gimle.local", 1);

    assertEquals(2, unsignedShort(response, 6)); // ANCOUNT: the headless posture, all endpoints
    List<byte[]> rdatas = answerRdatas(response, "orders.svc.gimle.local");
    assertArrayEquals(new byte[] {10, 0, 0, 5}, rdatas.get(0));
    assertArrayEquals(new byte[] {10, 0, 0, 6}, rdatas.get(1));
  }

  @Test
  void an_srv_query_answers_one_record_per_endpoint_with_its_own_port() throws IOException {
    directory.replaceAll(
        Map.of(
            "orders.acme",
            List.of(new HostPort("10.0.0.5", 8080), new HostPort("10.0.0.6", 9090))));

    byte[] response = query(0x7, "orders.acme.svc.gimle.local", 33); // SRV

    assertEquals(0, unsignedShort(response, 2) & 0xF); // RCODE: NOERROR
    assertEquals(2, unsignedShort(response, 6)); // ANCOUNT
    List<byte[]> rdatas = answerRdatas(response, "orders.acme.svc.gimle.local");
    assertEquals(8080, unsignedShort(rdatas.get(0), 4)); // SRV port at rdata offset 4
    assertEquals(9090, unsignedShort(rdatas.get(1), 4));
    assertEquals("10-0-0-5.orders.acme.svc.gimle.local", srvTargetName(rdatas.get(0)));
    assertEquals("10-0-0-6.orders.acme.svc.gimle.local", srvTargetName(rdatas.get(1)));
  }

  @Test
  void a_dashed_endpoint_name_resolves_to_exactly_that_endpoint_address() throws IOException {
    directory.replaceAll(
        Map.of(
            "orders.acme",
            List.of(new HostPort("10.0.0.5", 8080), new HostPort("10.0.0.6", 8080))));

    byte[] response = query(0x8, "10-0-0-6.orders.acme.svc.gimle.local", 1);

    assertEquals(0, unsignedShort(response, 2) & 0xF); // RCODE: NOERROR
    assertEquals(1, unsignedShort(response, 6)); // ANCOUNT
    assertArrayEquals(
        new byte[] {10, 0, 0, 6}, answerRdata(response, "10-0-0-6.orders.acme.svc.gimle.local"));
  }

  @Test
  void a_dashed_endpoint_name_not_in_the_service_answers_nxdomain() throws IOException {
    directory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));

    byte[] response = query(0x9, "10-9-9-9.orders.acme.svc.gimle.local", 1);

    assertEquals(3, unsignedShort(response, 2) & 0xF); // RCODE: NXDOMAIN
  }

  @Test
  void answers_unknown_name_with_nxdomain() throws IOException {
    byte[] response = query(0x2222, "missing.svc.gimle.local", 1);

    assertEquals(0x2222, unsignedShort(response, 0));
    int flags = unsignedShort(response, 2);
    assertEquals(3, flags & 0xF); // RCODE: NXDOMAIN
    assertEquals(0, unsignedShort(response, 6)); // ANCOUNT
  }

  @Test
  void answers_unsupported_query_type_with_notimp() throws IOException {
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));

    byte[] response = query(0x3333, "orders.svc.gimle.local", 28); // AAAA, not A

    assertEquals(0x3333, unsignedShort(response, 0));
    int flags = unsignedShort(response, 2);
    assertEquals(4, flags & 0xF); // RCODE: NOTIMP
    assertEquals(0, unsignedShort(response, 6)); // ANCOUNT
  }

  @Test
  void answers_unsupported_opcode_with_notimp() throws IOException {
    byte[] response = query(0x4444, "orders.svc.gimle.local", 1, /* opcode= */ 1);

    int flags = unsignedShort(response, 2);
    assertEquals(4, flags & 0xF); // RCODE: NOTIMP
  }

  @Test
  void drops_a_malformed_datagram_instead_of_replying() throws IOException {
    byte[] garbage = new byte[] {1, 2, 3}; // shorter than a 12-byte header
    clientSocket.send(
        new DatagramPacket(
            garbage, garbage.length, InetAddress.getLoopbackAddress(), server.port()));

    clientSocket.setSoTimeout(500);
    byte[] buf = new byte[512];
    assertThrows(
        SocketTimeoutException.class,
        () -> clientSocket.receive(new DatagramPacket(buf, buf.length)));
  }

  @Test
  void answers_the_same_query_over_tcp_with_a_length_prefixed_response() throws IOException {
    directory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));

    try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
      tcp.setSoTimeout(5_000);
      byte[] response = tcpQuery(tcp, 0x5555, "orders.acme.svc.gimle.local");

      assertEquals(0x5555, unsignedShort(response, 0));
      int flags = unsignedShort(response, 2);
      assertEquals(1, (flags >>> 15) & 0x1); // QR: response
      assertEquals(0, (flags >>> 9) & 0x1); // TC clear: TCP always carries the full answer
      assertEquals(0, flags & 0xF); // RCODE: NOERROR
      assertEquals(1, unsignedShort(response, 6)); // ANCOUNT
      assertArrayEquals(
          new byte[] {10, 0, 0, 5}, answerRdata(response, "orders.acme.svc.gimle.local"));
    }
  }

  @Test
  void serves_multiple_sequential_queries_on_one_tcp_connection() throws IOException {
    directory.replaceAll(
        Map.of("orders", List.of(new HostPort("10.0.0.5", 8080), new HostPort("10.0.0.6", 8080))));

    try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
      tcp.setSoTimeout(5_000);
      byte[] first = tcpQuery(tcp, 0x1, "orders.svc.gimle.local");
      byte[] second = tcpQuery(tcp, 0x2, "orders.svc.gimle.local");

      assertEquals(2, unsignedShort(first, 6));
      assertEquals(2, unsignedShort(second, 6));
      assertArrayEquals(new byte[] {10, 0, 0, 5}, answerRdata(first, "orders.svc.gimle.local"));
    }
  }

  @Test
  void answers_unknown_name_over_tcp_with_nxdomain() throws IOException {
    try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
      tcp.setSoTimeout(5_000);
      byte[] response = tcpQuery(tcp, 0x6666, "missing.svc.gimle.local");

      assertEquals(3, unsignedShort(response, 2) & 0xF); // RCODE: NXDOMAIN
      assertEquals(0, unsignedShort(response, 6)); // ANCOUNT
    }
  }

  @Test
  void refuses_a_positive_answer_with_servfail_once_severely_stale() throws IOException {
    TestClock clock = new TestClock();
    CachingServiceDirectory staleDirectory = new CachingServiceDirectory(clock);
    staleDirectory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));
    Duration threshold = Duration.ofSeconds(30);
    try (SkaldServer staleServer = new SkaldServer(staleDirectory, 0, threshold)) {
      // Still fresh: a normal answer.
      byte[] fresh = query(staleServer.port(), 0x1111, "orders.acme.svc.gimle.local", 1);
      assertEquals(0, unsignedShort(fresh, 2) & 0xF); // RCODE: NOERROR

      clock.advance(threshold.plusSeconds(1));

      byte[] stale = query(staleServer.port(), 0x2222, "orders.acme.svc.gimle.local", 1);
      assertEquals(2, unsignedShort(stale, 2) & 0xF); // RCODE: SERVFAIL
      assertEquals(0, unsignedShort(stale, 6)); // ANCOUNT: no answers offered
    }
  }

  @Test
  void a_name_the_directory_never_knew_still_answers_nxdomain_once_stale() throws IOException {
    // Staleness makes a *positive* answer untrustworthy, not a negative one -- a name genuinely
    // absent from the cache stays NXDOMAIN either way, the same clean failure a caller already
    // handles, rather than escalating every miss to SERVFAIL too.
    TestClock clock = new TestClock();
    CachingServiceDirectory staleDirectory = new CachingServiceDirectory(clock);
    Duration threshold = Duration.ofSeconds(30);
    clock.advance(threshold.plusSeconds(1)); // stale from birth: never a successful poll
    try (SkaldServer staleServer = new SkaldServer(staleDirectory, 0, threshold)) {
      byte[] response = query(staleServer.port(), 0x3333, "missing.svc.gimle.local", 1);
      assertEquals(3, unsignedShort(response, 2) & 0xF); // RCODE: NXDOMAIN
    }
  }

  @Test
  void a_fresh_successful_poll_immediately_ends_the_servfail_degradation() throws IOException {
    TestClock clock = new TestClock();
    CachingServiceDirectory staleDirectory = new CachingServiceDirectory(clock);
    staleDirectory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));
    Duration threshold = Duration.ofSeconds(30);
    try (SkaldServer staleServer = new SkaldServer(staleDirectory, 0, threshold)) {
      clock.advance(threshold.plusSeconds(1));
      byte[] stale = query(staleServer.port(), 0x4444, "orders.acme.svc.gimle.local", 1);
      assertEquals(2, unsignedShort(stale, 2) & 0xF); // RCODE: SERVFAIL

      staleDirectory.replaceAll(Map.of("orders.acme", List.of(new HostPort("10.0.0.5", 8080))));

      byte[] recovered = query(staleServer.port(), 0x5555, "orders.acme.svc.gimle.local", 1);
      int recoveredFlags = unsignedShort(recovered, 2);
      assertNotEquals(2, recoveredFlags & 0xF); // no longer SERVFAIL
      assertEquals(0, recoveredFlags & 0xF); // RCODE: NOERROR
    }
  }

  /** Sends one RFC 1035 §4.2.2 length-prefixed query and reads the length-prefixed response. */
  private static byte[] tcpQuery(Socket tcp, int id, String name) throws IOException {
    byte[] request = buildQuery(id, name, /* qtype= */ 1, /* opcode= */ 0);
    DataOutputStream out = new DataOutputStream(tcp.getOutputStream());
    out.writeShort(request.length);
    out.write(request);
    out.flush();
    DataInputStream in = new DataInputStream(tcp.getInputStream());
    byte[] response = new byte[in.readUnsignedShort()];
    in.readFully(response);
    return response;
  }

  private byte[] query(int id, String name, int qtype) throws IOException {
    return query(id, name, qtype, /* opcode= */ 0);
  }

  private byte[] query(int id, String name, int qtype, int opcode) throws IOException {
    return query(server.port(), id, name, qtype, opcode);
  }

  /**
   * Targets an explicit port over the shared {@link #clientSocket}, for a server other than {@link
   * #server}.
   */
  private byte[] query(int port, int id, String name, int qtype) throws IOException {
    return query(port, id, name, qtype, /* opcode= */ 0);
  }

  private byte[] query(int port, int id, String name, int qtype, int opcode) throws IOException {
    byte[] request = buildQuery(id, name, qtype, opcode);
    clientSocket.send(
        new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
    byte[] buf = new byte[512];
    DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
    clientSocket.receive(responsePacket);
    return Arrays.copyOf(buf, responsePacket.getLength());
  }

  private static byte[] buildQuery(int id, String name, int qtype, int opcode) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeShort(id);
    int flags = 0;
    flags |= (opcode & 0xF) << 11;
    flags |= 1 << 8; // RD=1, matching a real resolver's own default
    out.writeShort(flags);
    out.writeShort(1); // QDCOUNT
    out.writeShort(0); // ANCOUNT
    out.writeShort(0); // NSCOUNT
    out.writeShort(0); // ARCOUNT
    out.write(encodeName(name));
    out.writeShort(qtype);
    out.writeShort(1); // QCLASS: IN
    return buffer.toByteArray();
  }

  private static byte[] encodeName(String dotted) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String label : dotted.split("\\.")) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.write(bytes.length);
      out.write(bytes);
    }
    out.write(0);
    return out.toByteArray();
  }

  /** Locates and extracts the 4-byte RDATA of a response's first (and only) answer record. */
  private static byte[] answerRdata(byte[] response, String queriedName) throws IOException {
    int questionEnd = 12 + encodeName(queriedName).length + 4; // header + QNAME + QTYPE/QCLASS
    int rdataOffset = questionEnd + 2 + 2 + 2 + 4 + 2; // NAME ptr + TYPE + CLASS + TTL + RDLENGTH
    byte[] rdata = new byte[4];
    System.arraycopy(response, rdataOffset, rdata, 0, 4);
    return rdata;
  }

  /** Parses every answer record's RDATA, in order, from a response to {@code queriedName}. */
  private static List<byte[]> answerRdatas(byte[] response, String queriedName) throws IOException {
    int answerCount = unsignedShort(response, 6);
    int offset = 12 + encodeName(queriedName).length + 4; // header + QNAME + QTYPE/QCLASS
    List<byte[]> rdatas = new java.util.ArrayList<>();
    for (int i = 0; i < answerCount; i++) {
      offset += 2 + 2 + 2 + 4; // NAME pointer + TYPE + CLASS + TTL
      int rdLength = unsignedShort(response, offset);
      offset += 2;
      byte[] rdata = new byte[rdLength];
      System.arraycopy(response, offset, rdata, 0, rdLength);
      rdatas.add(rdata);
      offset += rdLength;
    }
    return rdatas;
  }

  /** Decodes an uncompressed name starting at offset 0 of a CNAME RDATA into dotted-label form. */
  private static String cnameTargetName(byte[] cnameRdata) {
    StringBuilder name = new StringBuilder();
    int pos = 0;
    while (cnameRdata[pos] != 0) {
      int length = cnameRdata[pos] & 0xFF;
      pos++;
      if (name.length() > 0) {
        name.append('.');
      }
      name.append(new String(cnameRdata, pos, length, StandardCharsets.US_ASCII));
      pos += length;
    }
    return name.toString();
  }

  /** Decodes the uncompressed target name at offset 6 of an SRV RDATA into dotted-label form. */
  private static String srvTargetName(byte[] srvRdata) {
    StringBuilder name = new StringBuilder();
    int pos = 6;
    while (srvRdata[pos] != 0) {
      int length = srvRdata[pos] & 0xFF;
      pos++;
      if (name.length() > 0) {
        name.append('.');
      }
      name.append(new String(srvRdata, pos, length, StandardCharsets.US_ASCII));
      pos += length;
    }
    return name.toString();
  }

  private static int unsignedShort(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
  }
}
