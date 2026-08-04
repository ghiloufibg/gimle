package com.gimle.fabric.transport;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
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
 * <p>The two cross-machine paths (plaintext TCP and TLS) bound both connect and read time -- a slow
 * or network-partitioned peer must not block the calling virtual thread indefinitely (confirmed
 * directly, not assumed: neither timeout was ever set here before this was added). The same-machine
 * UDS path deliberately keeps neither: its connect can't meaningfully hang in the first place -- no
 * network round-trip, kernel-mediated, per §1's own table in {@code
 * claudedocs/tls-transport-security-design.md} -- and bounding its read would need a materially
 * more complex mechanism ({@code SocketChannel} has no {@code setSoTimeout}; its {@link
 * SocketChannel#socket()} adapter throws {@code UnsupportedOperationException} for a
 * Unix-domain-family channel specifically, confirmed empirically, not assumed -- a real read
 * timeout here would mean non-blocking mode plus a {@code Selector}), disproportionate to a risk
 * that's specifically about network partitions, which a same-machine socket can't have.
 */
public final class FabricClient {

  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int READ_TIMEOUT_MILLIS = 30_000;

  private FabricClient() {}

  public static FabricFrame call(SocketAddress endpoint, FabricFrame.InvokeRequest request)
      throws IOException {
    if (endpoint instanceof UnixDomainSocketAddress) {
      try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        channel.connect(endpoint);
        return callOverStreams(
            endpoint, Channels.newInputStream(channel), Channels.newOutputStream(channel), request);
      }
    }
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      try (SocketChannel channel = SocketChannel.open()) {
        Socket socket = channel.socket();
        socket.connect(endpoint, CONNECT_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return callOverStreams(
            endpoint, socket.getInputStream(), socket.getOutputStream(), request);
      }
    }
    SocketFactory factory = SslContexts.forMutualTls(TlsSettings.fromConfig()).getSocketFactory();
    try (Socket socket = factory.createSocket()) {
      socket.connect(endpoint, CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      return callOverStreams(endpoint, socket.getInputStream(), socket.getOutputStream(), request);
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
