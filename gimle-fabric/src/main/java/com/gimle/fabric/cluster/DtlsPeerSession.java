package com.gimle.fabric.cluster;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * One peer's DTLS handshake/record state, wrapping a single {@link SSLEngine} in datagram mode
 * (JSSE's DTLS support is engine-based only -- there is no {@code DTLSSocket} equivalent of {@code
 * SSLSocket}, per {@code claudedocs/tls-transport-security-design.md} §2). All access is {@code
 * synchronized} on this instance: {@link SSLEngine} is explicitly documented as not safe for
 * concurrent {@code wrap}/{@code unwrap} from multiple threads, and {@link GossipMember} drives one
 * session from both its ticker (sends) and its receive loop (inbound datagrams).
 *
 * <p>Every method that can produce outbound handshake bytes returns them as a list of complete UDP
 * payloads for the caller to send -- this class never touches the network itself, keeping it
 * testable without a real socket.
 */
final class DtlsPeerSession {

  private static final ByteBuffer NO_INPUT = ByteBuffer.allocate(0);
  // A generous bound on how many WRAP/UNWRAP/TASK steps a single call can take -- purely a guard
  // against turning a real SSLEngine bug (or an engine implementation this code hasn't been
  // exercised against) into an infinite loop rather than a clean failure.
  private static final int MAX_LOOP_ITERATIONS = 64;

  private final SSLEngine engine;
  private volatile Instant handshakeStartedAt;
  private volatile boolean established;

  private DtlsPeerSession(SSLEngine engine) {
    this.engine = engine;
  }

  /**
   * A client-mode session: {@link GossipMember} creates one of these only when it has determined
   * (via {@code isDesignatedInitiator}) that it, not the peer, is responsible for originating the
   * handshake for this pair -- see that class for why that avoids simultaneous-ClientHello glare
   * entirely, rather than resolving it after the fact.
   */
  static DtlsPeerSession client(SSLContext context, InetSocketAddress peer) {
    SSLEngine engine = context.createSSLEngine(peer.getHostString(), peer.getPort());
    engine.setUseClientMode(true);
    return new DtlsPeerSession(engine);
  }

  /** A server-mode session: created reactively, the first time a datagram arrives from a peer. */
  static DtlsPeerSession server(SSLContext context) {
    SSLEngine engine = context.createSSLEngine();
    engine.setUseClientMode(false);
    engine.setNeedClientAuth(true);
    return new DtlsPeerSession(engine);
  }

  boolean isClient() {
    return engine.getUseClientMode();
  }

  boolean isEstablished() {
    return established;
  }

  /** {@code null} until the handshake has actually been started. */
  Instant handshakeStartedAt() {
    return handshakeStartedAt;
  }

  /** Starts the handshake, returning the initial flight (client: ClientHello) to send. */
  synchronized List<byte[]> beginHandshake() throws SSLException {
    handshakeStartedAt = Instant.now();
    engine.beginHandshake();
    return drive();
  }

  /**
   * Feeds one received datagram to the engine, driving the handshake (or, once established,
   * decrypting application data) as far as it can go without further network input. Returns both
   * any decrypted application payloads and any handshake response bytes that must be sent back --
   * either or both may be empty, which is the normal outcome mid-handshake while this side waits on
   * the peer's next flight.
   */
  synchronized Result unwrap(byte[] datagram) throws SSLException {
    List<byte[]> outbound = new ArrayList<>();
    List<byte[]> messages = new ArrayList<>();
    ByteBuffer netIn = ByteBuffer.wrap(datagram);

    for (int iteration = 0; iteration < MAX_LOOP_ITERATIONS; iteration++) {
      SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
      if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
          || status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN
          || status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
        if (!netIn.hasRemaining() && status != SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) {
          return new Result(messages, outbound);
        }
        ByteBuffer appOut = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        SSLEngineResult result = engine.unwrap(netIn, appOut);
        appOut.flip();
        if (appOut.hasRemaining()) {
          byte[] message = new byte[appOut.remaining()];
          appOut.get(message);
          messages.add(message);
        }
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
          established = true;
        }
        if (result.getStatus() == SSLEngineResult.Status.CLOSED
            || result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
          return new Result(messages, outbound);
        }
      } else if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        outbound.addAll(produceWrap());
      } else if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
        runDelegatedTasks();
      } else {
        // FINISHED is transient and already handled above via the unwrap result; seeing it here
        // (e.g. immediately after beginHandshake on the server side, before any data arrived) just
        // means there's nothing further to do without more network input.
        return new Result(messages, outbound);
      }
    }
    throw new SSLException(
        "DTLS handshake made no progress after " + MAX_LOOP_ITERATIONS + " steps");
  }

  /** Encrypts one application-layer payload (a single encoded {@code SwimMessage}) to send. */
  synchronized List<byte[]> wrap(byte[] payload) throws SSLException {
    ByteBuffer appIn = ByteBuffer.wrap(payload);
    ByteBuffer netOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
    SSLEngineResult result = engine.wrap(appIn, netOut);
    if (appIn.hasRemaining()) {
      // A gossip message that doesn't fit in a single DTLS record -- SwimCodec already bounds
      // datagram size to 65535 bytes, but a single TLS record is capped well below that; rather
      // than build multi-record reassembly for a case gossip payloads never approach in practice,
      // fail loudly so an unexpectedly huge payload isn't silently truncated.
      throw new SSLException(
          "gossip payload of " + payload.length + " bytes did not fit in a single DTLS record");
    }
    netOut.flip();
    if (!netOut.hasRemaining()) {
      return List.of();
    }
    byte[] packet = new byte[netOut.remaining()];
    netOut.get(packet);
    return List.of(packet);
  }

  private List<byte[]> drive() throws SSLException {
    List<byte[]> outbound = new ArrayList<>();
    for (int iteration = 0; iteration < MAX_LOOP_ITERATIONS; iteration++) {
      SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
      if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        outbound.addAll(produceWrap());
      } else if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
        runDelegatedTasks();
      } else {
        return outbound;
      }
    }
    throw new SSLException(
        "DTLS handshake made no progress after " + MAX_LOOP_ITERATIONS + " steps");
  }

  private List<byte[]> produceWrap() throws SSLException {
    ByteBuffer netOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
    SSLEngineResult result = engine.wrap(NO_INPUT, netOut);
    if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
      established = true;
    }
    netOut.flip();
    if (!netOut.hasRemaining()) {
      return List.of();
    }
    byte[] packet = new byte[netOut.remaining()];
    netOut.get(packet);
    return List.of(packet);
  }

  private void runDelegatedTasks() {
    Runnable task;
    while ((task = engine.getDelegatedTask()) != null) {
      task.run();
    }
  }

  /**
   * @param messages decrypted application-layer payloads produced by this call, in order
   * @param outboundPackets handshake response bytes (or none) that must be sent back to the peer
   */
  record Result(List<byte[]> messages, List<byte[]> outboundPackets) {}
}
