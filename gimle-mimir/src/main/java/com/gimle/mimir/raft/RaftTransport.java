package com.gimle.mimir.raft;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of the Raft RPC surface: a {@code ServerSocket} bound to a TCP address, one
 * virtual thread per accepted connection, modeled directly on {@code gimle-fabric}'s {@code
 * FabricServer} -- the same shape (long-lived connections served in a loop, not open-per-call),
 * since Raft peers hold connections open across many RPCs, exactly like fabric's own cross-hop
 * calls hold theirs. Plain blocking {@code Socket}/{@code ServerSocket} rather than NIO channels:
 * virtual threads make blocking I/O just as cheap here, and {@link javax.net.ssl.SSLServerSocket}
 * -- the only JSSE type with this classic streams-based shape -- has no channel-based equivalent;
 * unifying on {@code Socket} lets {@link #listen} pick a plain or TLS {@link ServerSocketFactory}
 * with the accept loop and codec layer (both stream-based already) completely unchanged either way.
 */
public final class RaftTransport implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RaftTransport.class);

  private final RaftRpcHandler handler;
  private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public RaftTransport(RaftRpcHandler handler) {
    this.handler = handler;
  }

  /** Binds a listener at {@code bindAddress} and returns the actual bound address. */
  public SocketAddress listen(SocketAddress bindAddress) throws IOException {
    ServerSocket serverSocket = serverSocketFactory().createServerSocket();
    if (serverSocket instanceof SSLServerSocket sslServerSocket) {
      sslServerSocket.setNeedClientAuth(true);
    }
    serverSocket.bind(bindAddress);
    listeners.add(serverSocket);
    SocketAddress boundAddress = serverSocket.getLocalSocketAddress();
    Thread.ofVirtual()
        .name("gimle-raft-listener-" + boundAddress)
        .start(() -> acceptLoop(serverSocket));
    return boundAddress;
  }

  private static ServerSocketFactory serverSocketFactory() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return ServerSocketFactory.getDefault();
    }
    return SslContexts.forMutualTls(TlsSettings.fromConfig()).getServerSocketFactory();
  }

  /**
   * Rotation hot-swap: closes and rebinds every currently open TLS listener at the same address,
   * picking up whatever certificate material now sits at {@code gimle.tls.certFile}/ {@code
   * keyFile} (already overwritten by the caller before this runs) via {@link
   * #serverSocketFactory()}, which already reads {@link TlsSettings#fromConfig()} fresh. New
   * connection attempts during the brief close-to-rebind window fail and should be retried by the
   * caller; already-established connections are unaffected. No-op in plaintext mode.
   */
  public synchronized void reloadTlsMaterial() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    for (ServerSocket serverSocket : List.copyOf(listeners)) {
      if (!(serverSocket instanceof SSLServerSocket)) {
        continue;
      }
      SocketAddress address = serverSocket.getLocalSocketAddress();
      listeners.remove(serverSocket);
      try {
        serverSocket.close();
      } catch (IOException e) {
        log.warn("failed to close raft listener during reload: {}", e.getMessage());
      }
      try {
        listen(address);
      } catch (IOException e) {
        log.error(
            "failed to rebind raft listener at {} after TLS reload: {}", address, e.getMessage());
      }
    }
    log.info("reloaded TLS material for raft transport");
  }

  private void acceptLoop(ServerSocket serverSocket) {
    while (!closed && !serverSocket.isClosed()) {
      Socket connection;
      try {
        connection = serverSocket.accept();
        connection.setTcpNoDelay(true); // see PeerConnection: the response half of the round trip
      } catch (IOException e) {
        if (!closed) {
          log.warn("raft transport accept loop failed: {}", e.getMessage());
        }
        return;
      }
      Thread.ofVirtual().name("gimle-raft-connection").start(() -> serve(connection));
    }
  }

  private void serve(Socket connection) {
    try (connection) {
      InputStream in = connection.getInputStream();
      OutputStream out = connection.getOutputStream();
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
    for (ServerSocket socket : listeners) {
      try {
        socket.close();
      } catch (IOException e) {
        log.warn("failed to close raft listener: {}", e.getMessage());
      }
    }
  }
}
