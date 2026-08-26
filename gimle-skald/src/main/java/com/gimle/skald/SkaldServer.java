package com.gimle.skald;

import com.gimle.skald.directory.HostPort;
import com.gimle.skald.directory.ServiceDirectory;
import com.gimle.skald.dns.DnsCodec;
import com.gimle.skald.dns.ServiceDnsNames;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DNS responder itself: binds one {@link DatagramSocket} and one {@link ServerSocket} on the
 * same port, decodes each incoming query, resolves it against a {@link ServiceDirectory}, and
 * replies. Scope is deliberately narrow (see the design this component implements): standard {@code
 * A} and {@code SRV} queries against the {@code svc.gimle.local} zone get real answers (see {@link
 * #buildResponse} for the two name shapes served); a query this server can't or won't answer (wrong
 * opcode, an unsupported type, or a name outside the zone or not currently cached) gets a
 * well-formed {@code NOTIMP}/{@code NXDOMAIN} response rather than silence, so a caller's resolver
 * fails fast instead of timing out.
 *
 * <p>TCP serves the RFC 1035 §4.2.2 fallback contract: a UDP response that would exceed the
 * unextended 512-byte ceiling is sent truncated ({@code TC=1}, no answers), telling the resolver to
 * retry the identical query over TCP -- where each message is two-byte-length-prefixed and the full
 * response always fits. Both transports resolve through the identical code path, so an answer never
 * differs by transport.
 */
public final class SkaldServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SkaldServer.class);

  /**
   * RFC 1035 §4.2.1's unextended DNS-over-UDP ceiling; a response bigger than this is truncated on
   * UDP and served whole over TCP instead.
   */
  private static final int MAX_UDP_PACKET_BYTES = 512;

  /** A TCP message's two-byte length prefix caps one DNS message at 65535 bytes. */
  private static final int MAX_TCP_MESSAGE_BYTES = 0xFFFF;

  private final DatagramSocket socket;
  private final ServerSocket tcpSocket;
  private final ServiceDirectory directory;
  private final Thread listenerThread;
  private final Thread tcpListenerThread;
  private volatile boolean closed;

  public SkaldServer(ServiceDirectory directory, int port) throws IOException {
    this.directory = directory;
    this.socket = new DatagramSocket(port);
    // Same port number on both transports, as a resolver expects of a DNS server -- with port 0
    // the UDP bind picks first and TCP follows it, so port() is one answer for both.
    this.tcpSocket = new ServerSocket(socket.getLocalPort());
    this.listenerThread =
        Thread.ofPlatform().name("gimle-skald-udp-listener").start(this::listenLoop);
    this.tcpListenerThread =
        Thread.ofPlatform().name("gimle-skald-tcp-listener").start(this::tcpAcceptLoop);
  }

  /** The port actually bound (UDP and TCP alike) -- useful when constructed with 0 in tests. */
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
    if (response.length > MAX_UDP_PACKET_BYTES) {
      // Too big for unextended UDP: answers dropped, TC set, the resolver retries over TCP.
      response = DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of(), true);
    }
    try {
      socket.send(new DatagramPacket(response, response.length, from));
    } catch (IOException e) {
      log.warn("failed to send a DNS response to {}: {}", from, e.getMessage());
    }
  }

  private void tcpAcceptLoop() {
    while (!closed) {
      Socket connection;
      try {
        connection = tcpSocket.accept();
      } catch (IOException e) {
        if (closed) {
          return; // expected: close() closing the server socket unblocks this accept() call
        }
        log.warn("failed to accept a DNS TCP connection: {}", e.getMessage());
        continue;
      }
      Thread.ofVirtual().name("gimle-skald-tcp-query").start(() -> handleTcpConnection(connection));
    }
  }

  /**
   * One connection can carry any number of length-prefixed queries in sequence (RFC 1035 §4.2.2);
   * each is answered in arrival order on the same connection until the client closes or sends
   * something malformed -- a garbled message tears the whole connection down rather than replying,
   * the TCP analogue of {@link #handleDatagram}'s drop posture, since a framing error leaves no
   * trustworthy boundary to resynchronize on.
   */
  private void handleTcpConnection(Socket connection) {
    try (connection) {
      DataInputStream in = new DataInputStream(connection.getInputStream());
      DataOutputStream out = new DataOutputStream(connection.getOutputStream());
      while (!closed) {
        int length;
        try {
          length = in.readUnsignedShort();
        } catch (EOFException e) {
          return; // clean end of the connection: the client is done asking
        }
        byte[] message = new byte[length];
        in.readFully(message);
        DnsCodec.Query query;
        try {
          query = DnsCodec.decodeQuery(message, message.length);
        } catch (IllegalArgumentException e) {
          log.debug(
              "closing DNS TCP connection from {} on malformed message: {}",
              connection.getRemoteSocketAddress(),
              e.getMessage());
          return;
        }
        byte[] response = buildResponse(query);
        if (response.length > MAX_TCP_MESSAGE_BYTES) {
          // Unreachable for any realistic endpoint set, but the length prefix physically cannot
          // carry more -- refuse the connection rather than write a corrupt frame.
          log.warn("DNS response exceeds the 65535-byte TCP message ceiling; closing connection");
          return;
        }
        out.writeShort(response.length);
        out.write(response);
        out.flush();
      }
    } catch (IOException e) {
      if (!closed) {
        log.debug("DNS TCP connection ended abnormally: {}", e.getMessage());
      }
    }
  }

  /**
   * Two name shapes resolve within the zone, both against the same directory data:
   *
   * <ul>
   *   <li>{@code <service>[.<tenant>].svc.gimle.local} -- the Service itself. An {@code A} query
   *       answers every live endpoint address at once (the headless posture: the resolver does its
   *       own selection); an {@code SRV} query answers one record per endpoint, each carrying that
   *       endpoint's own port and a per-endpoint target name of the dashed-address form below.
   *   <li>{@code <a-b-c-d>.<service>[.<tenant>].svc.gimle.local} -- one specific endpoint, the
   *       dashed form of its IPv4 address (the per-endpoint hostname convention Kubernetes'
   *       headless Services also use). An {@code A} query answers exactly that address, provided
   *       the endpoint currently belongs to the service -- which is what makes an SRV target
   *       returned a moment ago actually resolvable.
   * </ul>
   *
   * <p>A name that exists but has no records of the queried type (an {@code SRV} for a dashed
   * endpoint name) answers {@code NOERROR} with zero answers -- the NODATA shape a real
   * authoritative server uses -- rather than {@code NXDOMAIN}, which would claim the name itself
   * doesn't exist.
   */
  private byte[] buildResponse(DnsCodec.Query query) {
    if (query.opcode() != DnsCodec.OPCODE_QUERY) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOTIMP, List.of());
    }
    int qtype = query.question().qtype();
    if ((qtype != DnsCodec.TYPE_A && qtype != DnsCodec.TYPE_SRV)
        || query.question().qclass() != DnsCodec.CLASS_IN) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOTIMP, List.of());
    }
    Optional<String> qualifiedName = ServiceDnsNames.qualifiedServiceName(query.question().name());
    if (qualifiedName.isEmpty()) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }

    Optional<EndpointName> endpointName = EndpointName.parse(qualifiedName.get());
    if (endpointName.isPresent()) {
      return buildEndpointResponse(query, qtype, endpointName.get());
    }

    List<HostPort> endpoints = directory.resolveAll(qualifiedName.get());
    if (endpoints.isEmpty()) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }
    if (qtype == DnsCodec.TYPE_A) {
      List<DnsCodec.Answer> answers = new ArrayList<>();
      Set<String> seenHosts = new LinkedHashSet<>();
      for (HostPort endpoint : endpoints) {
        if (!seenHosts.add(endpoint.host())) {
          continue; // two endpoints on one host (distinct ports) are still one A record
        }
        parseIpv4(endpoint.host()).ifPresent(address -> answers.add(DnsCodec.Answer.a(address)));
      }
      if (answers.isEmpty()) {
        // An ExternalName Service's endpoint host is a name outside this zone, not an address --
        // the truthful answer is a CNAME alias to it, letting the caller's own resolver finish
        // the resolution (Kubernetes' own ExternalName contract).
        Optional<String> externalHost =
            endpoints.stream()
                .map(HostPort::host)
                .filter(host -> parseIpv4(host).isEmpty())
                .findFirst();
        if (externalHost.isPresent()) {
          return DnsCodec.encodeResponse(
              query,
              DnsCodec.RCODE_NOERROR,
              List.of(DnsCodec.Answer.cname(hostLabels(externalHost.get()))),
              false);
        }
        return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
      }
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, answers, false);
    }
    List<DnsCodec.Answer> answers = new ArrayList<>();
    for (HostPort endpoint : endpoints) {
      List<String> targetLabels;
      if (parseIpv4(endpoint.host()).isPresent()) {
        targetLabels = new ArrayList<>();
        targetLabels.add(endpoint.host().replace('.', '-'));
        targetLabels.addAll(query.question().labels());
      } else {
        // An external host's own labels verbatim -- there is no dashed in-zone form to point at.
        targetLabels = hostLabels(endpoint.host());
      }
      answers.add(DnsCodec.Answer.srv(0, 0, endpoint.port(), targetLabels));
    }
    return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, answers, false);
  }

  /** A hostname's dot-separated labels, empties dropped (a trailing dot yields none). */
  private static List<String> hostLabels(String host) {
    List<String> labels = new ArrayList<>();
    for (String label : host.split("\\.")) {
      if (!label.isBlank()) {
        labels.add(label);
      }
    }
    return labels;
  }

  private byte[] buildEndpointResponse(DnsCodec.Query query, int qtype, EndpointName endpointName) {
    boolean known =
        directory.resolveAll(endpointName.serviceName()).stream()
            .anyMatch(endpoint -> endpoint.host().equals(endpointName.host()));
    if (!known) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NXDOMAIN, List.of());
    }
    if (qtype != DnsCodec.TYPE_A) {
      return DnsCodec.encodeResponse(query, DnsCodec.RCODE_NOERROR, List.of());
    }
    return DnsCodec.encodeResponse(
        query,
        DnsCodec.RCODE_NOERROR,
        List.of(DnsCodec.Answer.a(parseIpv4(endpointName.host()).orElseThrow())),
        false);
  }

  /**
   * A dashed-address endpoint label plus the service name behind it: {@code "10-0-0-5.orders.acme"}
   * parses to host {@code 10.0.0.5} and service {@code orders.acme}. Empty when the first label
   * isn't a well-formed dashed IPv4 address -- then the whole name is just a service name whose
   * first label happens to contain digits.
   */
  private record EndpointName(String host, String serviceName) {

    private static final Pattern DASHED_IPV4 =
        Pattern.compile("\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3}");

    static Optional<EndpointName> parse(String qualifiedName) {
      int firstDot = qualifiedName.indexOf('.');
      if (firstDot < 0) {
        return Optional.empty();
      }
      String firstLabel = qualifiedName.substring(0, firstDot);
      if (!DASHED_IPV4.matcher(firstLabel).matches()) {
        return Optional.empty();
      }
      String host = firstLabel.replace('-', '.');
      if (parseIpv4(host).isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new EndpointName(host, qualifiedName.substring(firstDot + 1)));
    }
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
    try {
      tcpSocket.close();
    } catch (IOException e) {
      log.warn("failed to close DNS TCP listener socket: {}", e.getMessage());
    }
    listenerThread.interrupt();
    tcpListenerThread.interrupt();
  }
}
