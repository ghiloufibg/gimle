package com.gimle.fabric.transport;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.SocketFactory;

/**
 * The calling side of a cross-hop service call: opens one connection per invocation (same-machine
 * UDS -- always plaintext -- or cross-machine TCP, chosen by {@code endpoint}'s address type, TCP
 * further gated on {@link TransportProtocol#fromConfig()} between a plain {@code SocketChannel} and
 * a TLS {@link javax.net.ssl.SSLSocket}, the same swap {@code PeerConnection} makes for Raft),
 * sends a single {@link FabricFrame.InvokeRequest}, and blocks for the matching response. Kept
 * deliberately simple -- one connection per call rather than a pooled/multiplexed client --
 * matching this project's MVP-first convention; virtual threads make the per-call connection/thread
 * cost cheap on both ends (a synchronous proxy call is exactly one virtual thread blocked on
 * exactly one connection).
 *
 * <p>Connect, write, and read are bounded together by one {@code timeout}, run on a second virtual
 * thread so the calling thread can enforce the deadline via {@link Future#get(long, TimeUnit)}
 * rather than any transport-specific timeout API ({@code SocketChannel} has no blocking-mode
 * timeout at all; {@link Socket} does, but only for {@code connect}/read separately) -- one
 * mechanism that works identically across UDS/plaintext-TCP/TLS. On timeout, the underlying
 * channel/socket is closed to unblock whichever I/O call the worker thread is stuck in, and the
 * timeout surfaces as an {@link IOException} -- the same exception type {@link
 * com.gimle.fabric.registry.FabricServiceRegistry}'s circuit breaker already keys off of, so a hung
 * peer trips the breaker exactly like a refused connection or a truncated response, closing what
 * was previously a real gap: nothing here bounded how long a caller could hang.
 */
public final class FabricClient {

  /**
   * A few seconds: generous enough for a legitimately slow same-machine or LAN hop, short enough
   * that a caller isn't left hanging indefinitely on a peer that has wedged rather than refused or
   * reset the connection outright (both of which already fail fast without this timeout).
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private FabricClient() {}

  public static FabricFrame call(SocketAddress endpoint, FabricFrame.InvokeRequest request)
      throws IOException {
    return call(endpoint, request, DEFAULT_TIMEOUT);
  }

  public static FabricFrame call(
      SocketAddress endpoint, FabricFrame.InvokeRequest request, Duration timeout)
      throws IOException {
    if (endpoint instanceof UnixDomainSocketAddress) {
      SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
      return callOverChannel(endpoint, channel, request, timeout);
    }
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      SocketChannel channel = SocketChannel.open();
      return callOverChannel(endpoint, channel, request, timeout);
    }
    SocketFactory factory = SslContexts.forMutualTls(TlsSettings.fromConfig()).getSocketFactory();
    Socket socket = factory.createSocket();
    return callOverSocket(endpoint, socket, request, timeout);
  }

  private static FabricFrame callOverChannel(
      SocketAddress endpoint,
      SocketChannel channel,
      FabricFrame.InvokeRequest request,
      Duration timeout)
      throws IOException {
    return runBounded(
        endpoint,
        channel,
        timeout,
        () -> {
          try (channel) {
            channel.connect(endpoint);
            return callOverStreams(
                endpoint,
                Channels.newInputStream(channel),
                Channels.newOutputStream(channel),
                request);
          }
        });
  }

  private static FabricFrame callOverSocket(
      SocketAddress endpoint, Socket socket, FabricFrame.InvokeRequest request, Duration timeout)
      throws IOException {
    return runBounded(
        endpoint,
        socket,
        timeout,
        () -> {
          try (socket) {
            socket.connect(endpoint);
            return callOverStreams(
                endpoint, socket.getInputStream(), socket.getOutputStream(), request);
          }
        });
  }

  /**
   * Runs {@code task} (which owns closing {@code resource} itself on the happy path) on its own
   * virtual thread and enforces {@code timeout} from this thread; closes {@code resource} on
   * timeout to unblock the worker rather than leaving it to run forever in the background.
   */
  private static FabricFrame runBounded(
      SocketAddress endpoint, AutoCloseable resource, Duration timeout, Callable<FabricFrame> task)
      throws IOException {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<FabricFrame> future = executor.submit(task);
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        closeQuietly(resource);
        throw new SocketTimeoutException(
            "fabric call to " + endpoint + " timed out after " + timeout);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
          throw io;
        }
        throw new IOException("fabric call to " + endpoint + " failed", cause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        closeQuietly(resource);
        throw new InterruptedIOException("fabric call to " + endpoint + " interrupted");
      }
    } finally {
      executor.shutdown();
    }
  }

  private static void closeQuietly(AutoCloseable resource) {
    try {
      resource.close();
    } catch (Exception ignored) {
      // Best-effort: unblocking the worker thread is the point, not reporting a second failure.
    }
  }

  private static FabricFrame callOverStreams(
      SocketAddress endpoint, InputStream in, OutputStream out, FabricFrame.InvokeRequest request)
      throws IOException {
    FabricCodec.write(out, request);
    FabricFrame response = FabricCodec.read(in);
    if (response == null) {
      throw new EOFException(
          "fabric endpoint " + endpoint + " closed the connection without responding");
    }
    return response;
  }
}
