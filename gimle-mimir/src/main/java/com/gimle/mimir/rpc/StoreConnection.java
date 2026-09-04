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

  /**
   * Overridable via {@code -Dgimle.store.connectTimeoutMillis} -- the same
   * system-property-with-a-safe-default pattern {@code RaftNode}'s own {@code
   * gimle.raft.proposeTimeoutSeconds} already uses. Without this, a store endpoint that's reachable
   * but never completes the TCP handshake would block {@link #connectionLocked} forever instead of
   * failing over.
   */
  private static final int CONNECT_TIMEOUT_MILLIS =
      Integer.getInteger("gimle.store.connectTimeoutMillis", 3_000);

  /**
   * Overridable via {@code -Dgimle.store.readTimeoutMillis} -- bounds every blocking read on an
   * already-open connection, including the TLS handshake (which happens lazily on first I/O, not
   * inside {@link #connectionLocked} itself). A peer that accepts the connection but then goes
   * silent -- a gray failure, not a clean refusal -- surfaces as an ordinary {@link
   * java.net.SocketTimeoutException} instead of pinning the calling thread forever; {@link #call}
   * treats it exactly like any other {@link IOException}, so {@code StoreClient}'s failover logic
   * needs no special case for it.
   */
  private static final int READ_TIMEOUT_MILLIS =
      Integer.getInteger("gimle.store.readTimeoutMillis", 5_000);

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
    } catch (RuntimeException e) {
      // Anything that isn't an IOException still leaves this socket at an unknown offset in the
      // stream -- a frame this connection failed to decode was read partially or not at all, and
      // whatever remains would be read as the next call's response. Keeping the socket would turn
      // one failed call into a connection that fails every later call the same way until the
      // process restarts; dropping it costs a reconnect and nothing else.
      closeQuietlyLocked();
      throw e;
    } finally {
      lock.unlock();
    }
  }

  private Socket connectionLocked() throws IOException {
    if (socket == null || socket.isClosed()) {
      socket = socketFactory().createSocket();
      socket.connect(address, CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
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
