package com.gimle.mimir.raft;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.SocketFactory;

/**
 * The outbound side of the Raft RPC surface: one persistent, lazily-reconnecting {@code Socket} to
 * a single peer -- not {@code FabricClient}'s open-per-call pattern, because Raft contacts a fixed,
 * small peer set every 50ms indefinitely for the node's entire lifetime as leader/candidate, so a
 * fresh handshake per RPC is needless churn a fabric-style occasional cross-hop call doesn't have
 * to accept. Any {@link IOException} closes the socket and lets the *next* call attempt reopen it
 * -- failures are not retried within one call; a bounded gap in connectivity is acceptable here,
 * the same tolerance heartbeat repopulation after a leader change relies on, rather than inventing
 * a retry-with-backoff policy nothing asked for. Plain blocking {@code Socket}, not a NIO channel
 * -- see {@link RaftTransport}'s own javadoc for why: it's what lets {@link #socketFactory} swap in
 * TLS with zero change to this class's actual RPC logic.
 */
public final class PeerConnection implements RaftPeerClient, AutoCloseable {

  /**
   * Overridable via {@code -Dgimle.raft.connectTimeoutMillis} -- the same pattern {@code
   * StoreConnection} already uses. Without this, a peer that's reachable but never completes the
   * TCP handshake would block {@link #connectionLocked} forever instead of failing over.
   */
  private static final int CONNECT_TIMEOUT_MILLIS =
      Integer.getInteger("gimle.raft.connectTimeoutMillis", 3_000);

  /**
   * Overridable via {@code -Dgimle.raft.readTimeoutMillis} -- bounds every blocking read on an
   * already-open connection. A peer that accepts the connection but then goes silent -- a gray
   * failure, not a clean refusal -- surfaces as an ordinary {@link java.net.SocketTimeoutException}
   * instead of pinning the calling Raft sender thread forever; {@link #call} treats it exactly like
   * any other {@link IOException}.
   */
  private static final int READ_TIMEOUT_MILLIS =
      Integer.getInteger("gimle.raft.readTimeoutMillis", 5_000);

  private final SocketAddress address;
  private final ReentrantLock lock = new ReentrantLock();
  private Socket socket;

  public PeerConnection(SocketAddress address) {
    this.address = address;
  }

  @Override
  public RequestVoteResponse requestVote(RequestVote request) {
    return (RequestVoteResponse) call(request);
  }

  @Override
  public AppendEntriesResponse appendEntries(AppendEntries request) {
    return (AppendEntriesResponse) call(request);
  }

  @Override
  public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
    return (InstallSnapshotResponse) call(request);
  }

  private RaftRpc call(RaftRpc request) {
    lock.lock();
    try {
      Socket connection = connectionLocked();
      InputStream in = connection.getInputStream();
      OutputStream out = connection.getOutputStream();
      RaftCodec.write(out, request);
      RaftRpc response = RaftCodec.read(in);
      if (response == null) {
        closeQuietlyLocked();
        throw new UncheckedIOException(
            new IOException("peer " + address + " closed the connection"));
      }
      return response;
    } catch (IOException e) {
      closeQuietlyLocked();
      throw new UncheckedIOException(e);
    } finally {
      lock.unlock();
    }
  }

  private Socket connectionLocked() throws IOException {
    if (socket == null || socket.isClosed()) {
      socket = socketFactory().createSocket();
      socket.connect(address, CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      // Raft heartbeats every 50ms: a Nagle-induced delayed-ACK stall is comparable to the whole
      // heartbeat interval, so leaving it on directly destabilises election and commit timing.
      socket.setTcpNoDelay(true);
    }
    return socket;
  }

  private static SocketFactory socketFactory() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return SocketFactory.getDefault();
    }
    return SslContexts.forMutualTls(TlsSettings.fromConfig()).getSocketFactory();
  }

  private void closeQuietlyLocked() {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // best-effort; the next call reopens
      }
      socket = null;
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      closeQuietlyLocked();
    } finally {
      lock.unlock();
    }
  }
}
