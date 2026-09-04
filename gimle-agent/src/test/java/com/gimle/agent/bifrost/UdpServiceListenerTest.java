package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the datagram relay against real UDP sockets on loopback -- a real client, a real backend,
 * and no stubbing of the socket layer, since the reply-routing this class exists to do is precisely
 * what a mocked socket would assume rather than prove.
 */
class UdpServiceListenerTest {

  private final List<AutoCloseable> toClose = new ArrayList<>();

  @AfterEach
  void closeEverything() {
    for (AutoCloseable closeable : toClose) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // Best-effort teardown.
      }
    }
  }

  /**
   * A backend that answers every datagram with {@code prefix + payload}, so replies are traceable.
   */
  private EchoBackend startBackend(String prefix) throws IOException {
    EchoBackend backend = new EchoBackend(prefix);
    toClose.add(backend);
    return backend;
  }

  private UdpServiceListener startListener(List<ServiceEndpoint> endpoints) throws IOException {
    UdpServiceListener listener =
        new UdpServiceListener(
            "dns-service",
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            Optional.empty());
    toClose.add(listener);
    listener.updateEndpoints(endpoints);
    return listener;
  }

  private DatagramSocket clientSocket() throws IOException {
    DatagramSocket client = new DatagramSocket();
    client.setSoTimeout(3_000);
    toClose.add(client);
    return client;
  }

  private static void send(DatagramSocket client, InetSocketAddress to, String payload)
      throws IOException {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    client.send(new DatagramPacket(bytes, bytes.length, to));
  }

  private static String receive(DatagramSocket client) throws IOException {
    byte[] buffer = new byte[4096];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    client.receive(packet);
    return new String(
        packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
  }

  @Test
  void a_datagram_reaches_the_backend_and_its_reply_returns_to_the_client() throws Exception {
    EchoBackend backend = startBackend("echo:");
    UdpServiceListener listener = startListener(List.of(backend.endpoint()));
    DatagramSocket client = clientSocket();

    send(client, listener.boundAddress(), "hello");

    assertEquals("echo:hello", receive(client));
    assertEquals("hello", backend.received.poll());
  }

  @Test
  void a_client_keeps_talking_to_the_same_backend_across_datagrams() throws Exception {
    // One upstream socket per client is what makes reply routing possible at all, so a session's
    // backend is necessarily pinned -- asserted here so the property is not lost by accident.
    EchoBackend first = startBackend("a:");
    EchoBackend second = startBackend("b:");
    UdpServiceListener listener = startListener(List.of(first.endpoint(), second.endpoint()));
    DatagramSocket client = clientSocket();

    send(client, listener.boundAddress(), "one");
    String firstReply = receive(client);
    send(client, listener.boundAddress(), "two");
    String secondReply = receive(client);

    assertEquals(
        firstReply.substring(0, 2),
        secondReply.substring(0, 2),
        "both replies must come from the same backend");
  }

  @Test
  void two_clients_each_get_their_own_reply_rather_than_each_others() throws Exception {
    // The failure this guards against is the whole difficulty of a datagram relay: with one bound
    // socket, a listener that did not track which client sent what would answer the wrong one.
    EchoBackend backend = startBackend("echo:");
    UdpServiceListener listener = startListener(List.of(backend.endpoint()));
    DatagramSocket first = clientSocket();
    DatagramSocket second = clientSocket();

    send(first, listener.boundAddress(), "first-payload");
    send(second, listener.boundAddress(), "second-payload");

    assertEquals("echo:first-payload", receive(first));
    assertEquals("echo:second-payload", receive(second));
  }

  @Test
  void a_datagram_is_dropped_when_the_service_has_no_live_endpoints() throws Exception {
    UdpServiceListener listener = startListener(List.of());
    DatagramSocket client = clientSocket();
    client.setSoTimeout(500);

    send(client, listener.boundAddress(), "hello");

    assertThrows(SocketTimeoutException.class, () -> receive(client));
  }

  @Test
  void a_restricted_service_drops_every_datagram_because_a_relay_cannot_verify_a_caller()
      throws Exception {
    // The permanent limit of what Bifrost can enforce: no handshake means no caller identity, so
    // the only honest answer to an applicable policy is to relay nothing.
    EchoBackend backend = startBackend("echo:");
    UdpServiceListener listener = startListener(List.of(backend.endpoint()));
    listener.setApplicableRules(List.of(new NetworkPolicyRule("deny-all", "tenant-a", Set.of())));
    DatagramSocket client = clientSocket();
    client.setSoTimeout(500);

    send(client, listener.boundAddress(), "hello");

    assertThrows(SocketTimeoutException.class, () -> receive(client));
    assertTrue(backend.received.isEmpty(), "the backend must never have seen the datagram");
  }

  @Test
  void lifting_a_policy_lets_traffic_flow_again() throws Exception {
    EchoBackend backend = startBackend("echo:");
    UdpServiceListener listener = startListener(List.of(backend.endpoint()));
    listener.setApplicableRules(List.of(new NetworkPolicyRule("deny-all", "tenant-a", Set.of())));
    listener.setApplicableRules(List.of());
    DatagramSocket client = clientSocket();

    send(client, listener.boundAddress(), "hello");

    assertEquals("echo:hello", receive(client));
  }

  @Test
  void binds_at_a_specific_address_already_sharing_its_port_with_a_wildcard_socket()
      throws Exception {
    // Reproduces the collision a co-located UDP workload creates: it binds its own socket to the
    // wildcard address at some port (ordinary for a UDP server that wants any interface), and this
    // listener then needs a specific loopback address at that identical numeric port. Linux's UDP
    // overlap check treats a wildcard bind and a specific-address bind at the same port as
    // colliding unless SO_REUSEADDR is set on the socket already occupying the port as well as the
    // one binding after it -- true of a well-behaved UDP server that wants to coexist with other
    // listeners on the box, and exactly the case the old, always-reuse-off `new
    // DatagramSocket(bindAddress)` could never satisfy even when the workload's own socket
    // cooperated. Without a workload that opts in, no bind call on either side can make two
    // processes share one wildcard/specific pair -- that is a kernel-level limit, not a bug this
    // listener's own bind call can paper over.
    DatagramSocket wildcardWorkload = new DatagramSocket((SocketAddress) null);
    wildcardWorkload.setReuseAddress(true);
    wildcardWorkload.bind(new InetSocketAddress((InetAddress) null, 0));
    toClose.add(wildcardWorkload);
    int collidingPort = wildcardWorkload.getLocalPort();

    UdpServiceListener listener =
        new UdpServiceListener(
            "colliding-service",
            new InetSocketAddress(InetAddress.getLoopbackAddress(), collidingPort),
            Optional.empty());
    toClose.add(listener);

    assertEquals(collidingPort, listener.boundAddress().getPort());

    EchoBackend backend = startBackend("echo:");
    listener.updateEndpoints(List.of(backend.endpoint()));
    DatagramSocket client = clientSocket();
    send(client, listener.boundAddress(), "hello");
    assertEquals("echo:hello", receive(client));
  }

  @Test
  void a_full_size_datagram_survives_the_relay_intact() throws Exception {
    // Guards the buffer sizing: a smaller one would silently truncate rather than fail.
    EchoBackend backend = startBackend("");
    UdpServiceListener listener = startListener(List.of(backend.endpoint()));
    DatagramSocket client = clientSocket();
    byte[] payload = new byte[8_000];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i % 251);
    }

    client.send(new DatagramPacket(payload, payload.length, listener.boundAddress()));

    byte[] buffer = new byte[16_384];
    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
    client.receive(reply);
    byte[] returned = new byte[reply.getLength()];
    System.arraycopy(reply.getData(), reply.getOffset(), returned, 0, reply.getLength());
    assertArrayEquals(payload, returned);
  }

  /**
   * A real UDP server answering each datagram with {@code prefix} prepended to what it received.
   */
  private static final class EchoBackend implements AutoCloseable {

    private final DatagramSocket socket = new DatagramSocket();
    private final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean closed;

    EchoBackend(String prefix) throws IOException {
      thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    byte[] buffer = new byte[65_507];
                    while (!closed) {
                      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                      try {
                        socket.receive(packet);
                      } catch (IOException e) {
                        return;
                      }
                      byte[] body = new byte[packet.getLength()];
                      System.arraycopy(packet.getData(), packet.getOffset(), body, 0, body.length);
                      received.add(new String(body, StandardCharsets.UTF_8));
                      byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
                      byte[] reply = new byte[prefixBytes.length + body.length];
                      System.arraycopy(prefixBytes, 0, reply, 0, prefixBytes.length);
                      System.arraycopy(body, 0, reply, prefixBytes.length, body.length);
                      try {
                        socket.send(
                            new DatagramPacket(
                                reply, reply.length, packet.getAddress(), packet.getPort()));
                      } catch (IOException e) {
                        return;
                      }
                    }
                  });
    }

    ServiceEndpoint endpoint() {
      return new ServiceEndpoint(
          InetAddress.getLoopbackAddress().getHostAddress(), socket.getLocalPort());
    }

    @Override
    public void close() {
      closed = true;
      socket.close();
      thread.interrupt();
    }
  }
}
