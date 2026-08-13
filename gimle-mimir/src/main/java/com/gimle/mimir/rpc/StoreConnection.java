package com.gimle.mimir.rpc;

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
 * The outbound side of the client-facing store RPC surface: one persistent, lazily-reconnecting
 * {@code Socket} to a single {@code StoreNode} endpoint -- structurally identical to {@link
 * com.gimle.mimir.raft.PeerConnection}, a separate copy for the same reason {@link StoreTransport}
 * is a separate copy of {@code RaftTransport}: the two connect to different peers over different
 * protocols and are free to diverge. {@code StoreClient} (not this class) owns the pool of
 * endpoints and the leader-follow-retry policy; this class only knows how to talk to the one
 * address it was built with. Any {@link IOException} closes the socket and lets the *next* call
 * attempt reopen it, same tolerance {@code PeerConnection} already accepts rather than inventing a
 * retry-with-backoff policy here too.
 */
public final class StoreConnection implements AutoCloseable {

  private final SocketAddress address;
  private final ReentrantLock lock = new ReentrantLock();
  private Socket socket;

  public StoreConnection(SocketAddress address) {
    this.address = address;
  }

  public StoreRpc.Response call(StoreRpc.Request request) {
    lock.lock();
    try {
      Socket connection = connectionLocked();
      InputStream in = connection.getInputStream();
      OutputStream out = connection.getOutputStream();
      StoreCodec.write(out, request);
      StoreRpc response = StoreCodec.read(in);
      if (response == null) {
        closeQuietlyLocked();
        throw new UncheckedIOException(
            new IOException("store endpoint " + address + " closed the connection"));
      }
      if (!(response instanceof StoreRpc.Response typed)) {
        throw new IllegalStateException("store endpoint returned a non-response StoreRpc");
      }
      return typed;
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
      socket.connect(address);
      // Strict request/response over a long-lived connection: Nagle has nothing useful to batch
      // here, and left on it adds a delayed-ACK stall to every single call.
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
