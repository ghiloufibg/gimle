package com.gimle.controlplane.raft;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of the Raft RPC surface: a {@code ServerSocketChannel} bound to a TCP address,
 * one virtual thread per accepted connection, modeled directly on {@code gimle-fabric}'s {@code
 * FabricServer} -- the same shape (long-lived connections served in a loop, not open-per-call),
 * since Raft peers hold connections open across many RPCs, exactly like fabric's own cross-hop
 * calls hold theirs.
 */
public final class RaftTransport implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RaftTransport.class);

  private final RaftRpcHandler handler;
  private final List<ServerSocketChannel> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public RaftTransport(RaftRpcHandler handler) {
    this.handler = handler;
  }

  /** Binds a listener at {@code bindAddress} and returns the actual bound address. */
  public SocketAddress listen(SocketAddress bindAddress) throws IOException {
    ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.bind(bindAddress);
    listeners.add(serverChannel);
    SocketAddress boundAddress = serverChannel.getLocalAddress();
    Thread.ofVirtual()
        .name("gimle-raft-listener-" + boundAddress)
        .start(() -> acceptLoop(serverChannel));
    return boundAddress;
  }

  private void acceptLoop(ServerSocketChannel serverChannel) {
    while (!closed && serverChannel.isOpen()) {
      SocketChannel connection;
      try {
        connection = serverChannel.accept();
      } catch (IOException e) {
        if (!closed) {
          log.warn("raft transport accept loop failed: {}", e.getMessage());
        }
        return;
      }
      Thread.ofVirtual().name("gimle-raft-connection").start(() -> serve(connection));
    }
  }

  private void serve(SocketChannel connection) {
    try (connection) {
      var in = Channels.newInputStream(connection);
      var out = Channels.newOutputStream(connection);
      RaftRpc rpc;
      while ((rpc = RaftCodec.read(in)) != null) {
        RaftCodec.write(out, dispatch(rpc));
      }
    } catch (IOException e) {
      log.debug("raft connection closed: {}", e.getMessage());
    }
  }

  private RaftRpc dispatch(RaftRpc rpc) {
    return switch (rpc) {
      case RequestVote r -> handler.onRequestVote(r);
      case AppendEntries r -> handler.onAppendEntries(r);
      case InstallSnapshot r -> handler.onInstallSnapshot(r);
      default ->
          throw new IllegalArgumentException("raft server received an unexpected RPC: " + rpc);
    };
  }

  @Override
  public void close() {
    closed = true;
    for (ServerSocketChannel channel : listeners) {
      try {
        channel.close();
      } catch (IOException e) {
        log.warn("failed to close raft listener: {}", e.getMessage());
      }
    }
  }
}
