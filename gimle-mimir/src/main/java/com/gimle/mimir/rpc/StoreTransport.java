package com.gimle.mimir.rpc;

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
 * The receiving side of the client-facing store RPC surface: a {@code ServerSocket} bound to a TCP
 * address, one virtual thread per accepted connection -- structurally identical to {@link
 * com.gimle.mimir.raft.RaftTransport} (itself modeled on {@code gimle-fabric}'s {@code
 * FabricServer}), deliberately a separate copy rather than a shared base class (design doc §4.3:
 * the shared-transport-plumbing extraction is deferred to a later pass, unlike {@link
 * com.gimle.mimir.codec.DomainCodec}'s domain-encoding sharing, which wasn't). {@code StoreClient}
 * connections are shorter-lived and less frequent than Raft's own 50ms peer heartbeat, but the same
 * long-lived-connection-served-in-a-loop shape still fits (a client typically holds one connection
 * open across many requests rather than reconnecting per call).
 */
public final class StoreTransport implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StoreTransport.class);

  private final StoreRpcHandler handler;
  private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public StoreTransport(StoreRpcHandler handler) {
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
        .name("gimle-store-listener-" + boundAddress)
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
   * §6-style rotation hot-swap (matching {@code RaftTransport.reloadTlsMaterial}'s exact contract):
   * closes and rebinds every currently open TLS listener at the same address, picking up whatever
   * certificate material now sits at {@code gimle.tls.certFile}/{@code keyFile}. New connection
   * attempts during the brief close-to-rebind window fail and should be retried by the caller;
   * already-established connections are unaffected. No-op in plaintext mode.
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
        log.warn("failed to close store listener during reload: {}", e.getMessage());
      }
      try {
        listen(address);
      } catch (IOException e) {
        log.error(
            "failed to rebind store listener at {} after TLS reload: {}", address, e.getMessage());
      }
    }
    log.info("reloaded TLS material for store transport");
  }

  private void acceptLoop(ServerSocket serverSocket) {
    while (!closed && !serverSocket.isClosed()) {
      Socket connection;
      try {
        connection = serverSocket.accept();
        connection.setTcpNoDelay(true); // see StoreConnection: the response half of the round trip
      } catch (IOException e) {
        if (!closed) {
          log.warn("store transport accept loop failed: {}", e.getMessage());
        }
        return;
      }
      Thread.ofVirtual().name("gimle-store-connection").start(() -> serve(connection));
    }
  }

  private void serve(Socket connection) {
    try (connection) {
      InputStream in = connection.getInputStream();
      OutputStream out = connection.getOutputStream();
      StoreRpc rpc;
      while ((rpc = StoreCodec.read(in)) != null) {
        if (!(rpc instanceof StoreRpc.Request request)) {
          throw new IllegalArgumentException(
              "store server received a non-request StoreRpc: " + rpc);
        }
        StoreCodec.write(out, handler.handle(request));
      }
    } catch (IOException e) {
      log.debug("store connection closed: {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    closed = true;
    for (ServerSocket socket : listeners) {
      try {
        socket.close();
      } catch (IOException e) {
        log.warn("failed to close store listener: {}", e.getMessage());
      }
    }
  }
}
