package com.gimle.controlplane.raft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The outbound side of the Raft RPC surface: one persistent, lazily-reconnecting {@code
 * SocketChannel} to a single peer -- not {@code FabricClient}'s open-per-call pattern, because Raft
 * contacts a fixed, small peer set every 50ms indefinitely for the node's entire lifetime as
 * leader/candidate, so a fresh handshake per RPC is needless churn a fabric-style occasional
 * cross-hop call doesn't have to accept. Any {@link IOException} closes the channel and lets the
 * *next* call attempt reopen it -- failures are not retried within one call; a bounded gap in
 * connectivity is acceptable here, the same tolerance heartbeat repopulation after a leader change
 * relies on, rather than inventing a retry-with-backoff policy nothing asked for.
 */
public final class PeerConnection implements RaftPeerClient, AutoCloseable {

  private final SocketAddress address;
  private final ReentrantLock lock = new ReentrantLock();
  private SocketChannel channel;

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
      SocketChannel connection = connectionLocked();
      InputStream in = Channels.newInputStream(connection);
      OutputStream out = Channels.newOutputStream(connection);
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

  private SocketChannel connectionLocked() throws IOException {
    if (channel == null || !channel.isOpen()) {
      channel = SocketChannel.open(address);
    }
    return channel;
  }

  private void closeQuietlyLocked() {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // best-effort; the next call reopens
      }
      channel = null;
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
