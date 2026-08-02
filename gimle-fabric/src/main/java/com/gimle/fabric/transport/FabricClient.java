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
 */
public final class FabricClient {

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
        channel.connect(endpoint);
        return callOverStreams(
            endpoint, Channels.newInputStream(channel), Channels.newOutputStream(channel), request);
      }
    }
    SocketFactory factory = SslContexts.forMutualTls(TlsSettings.fromConfig()).getSocketFactory();
    try (Socket socket = factory.createSocket()) {
      socket.connect(endpoint);
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
