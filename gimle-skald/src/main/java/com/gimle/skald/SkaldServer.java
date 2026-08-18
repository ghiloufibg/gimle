package com.gimle.skald;

import com.gimle.skald.directory.ServiceDirectory;
import com.gimle.skald.dns.DnsCodec;
import com.gimle.skald.dns.ServiceDnsNames;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The UDP DNS responder itself: binds one {@link DatagramSocket}, decodes each incoming datagram as
 * a query, resolves it against a {@link ServiceDirectory}, and replies. Scope is deliberately
 * narrow (see the design this component implements): only a standard {@code A} query against the
 * {@code svc.gimle.local} zone gets a real answer; a query this server can't or won't answer (wrong
 * opcode, a non-{@code A} type, or a name outside the zone or not currently cached) gets a
 * well-formed {@code NOTIMP}/{@code NXDOMAIN} response rather than silence, so a caller's resolver
 * fails fast instead of timing out.
 */
public final class SkaldServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SkaldServer.class);

  /**
   * RFC 1035 §4.2.1's unextended DNS-over-UDP ceiling; a handful of A records never approaches it.
   */
  private static final int MAX_UDP_PACKET_BYTES = 512;

  private final DatagramSocket socket;
  private final ServiceDirectory directory;
  private final Thread listenerThread;
  private volatile boolean closed;

  public SkaldServer(ServiceDirectory directory, int port) throws IOException {
    this.directory = directory;
    this.socket = new DatagramSocket(port);
    this.listenerThread =
        Thread.ofPlatform().name("gimle-skald-udp-listener").start(this::listenLoop);
  }

  /** The UDP port actually bound -- useful when constructed with port {@code 0} in tests. */
  public int port() {
    return socket.getLocalPort();
  }

  private void listenLoop() {
    byte[] buffer = new byte[MAX_UDP_PACKET_BYTES];
    while (!closed) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (IOException e) {
        if (closed) {
          return; // expected: close() closing the socket unblocks this same receive() call
        }
        log.warn("failed to receive a DNS datagram: {}", e.getMessage());
        continue;
      }
      byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
      SocketAddress from = packet.getSocketAddress();
      // One virtual thread per query. Resolution never blocks on anything slower than a volatile
      // map read (the directory cache is refreshed by a separate poller, never on this path), so
      // this exists only to keep one slow send() to a client from delaying the next packet's
      // receive() call, not because real concurrent work happens here.
      Thread.ofVirtual().name("gimle-skald-query").start(() -> handleDatagram(data, from));
    }
  }

  private void handleDatagram(byte[] data, SocketAddress from) {
    DnsCodec.Query query;
    try {
      query = DnsCodec.decodeQuery(data, data.length);
    } catch (IllegalArgumentException e) {
      // Can't safely reply without at least a well-formed header/question to echo back -- the
      // same drop-don't-crash posture a real resolver takes toward a garbled datagram.
      log.debug("dropping malformed DNS datagram from {}: {}", from, e.getMessage());
      return;
    }
    byte[] response = buildResponse(query);
    try {
      socket.send(new DatagramPacket(response, response.length, from));
    } catch (IOException e) {
      log.warn("failed to send a DNS response to {}: {}", from, e.getMessage());
    }
  }

  private byte[] buildResponse(DnsCodec.Query query) {
    if (query.opcode() != DnsCodec.OPCODE_QUERY) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOTIMP, List.of());
    }
    if (query.question().qtype() != DnsCodec.TYPE_A
        || query.question().qclass() != DnsCodec.CLASS_IN) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOTIMP, List.of());
    }
    Optional<String> qualifiedName = ServiceDnsNames.qualifiedServiceName(query.question().name());
    if (qualifiedName.isEmpty()) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }
    Optional<String> host = directory.resolveOne(qualifiedName.get());
    if (host.isEmpty()) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }
    Optional<byte[]> address = parseIpv4(host.get());
    if (address.isEmpty()) {
      log.warn(
          "endpoint host {} for {} is not a dotted-decimal IPv4 address; answering NXDOMAIN",
          host.get(),
          qualifiedName.get());
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }
    return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of(address.get()));
  }

  /**
   * Parses a dotted-decimal IPv4 literal by hand rather than via {@code InetAddress.getByName} --
   * the endpoint hosts this server answers with come straight from the control plane's own catalog,
   * already numeric per the API contract, so there is never a hostname here needing a real
   * resolution; hand-parsing also means a malformed entry fails cleanly instead of risking a
   * name-service call this server has no business making.
   */
  private static Optional<byte[]> parseIpv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    byte[] address = new byte[4];
    for (int i = 0; i < 4; i++) {
      int octet;
      try {
        octet = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
      if (octet < 0 || octet > 255) {
        return Optional.empty();
      }
      address[i] = (byte) octet;
    }
    return Optional.of(address);
  }

  @Override
  public void close() {
    closed = true;
    socket.close();
    listenerThread.interrupt();
  }
}
