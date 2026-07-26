package com.gimle.fabric.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

/**
 * The calling side of a cross-hop service call: opens one connection per invocation (same-machine
 * UDS or cross-machine TCP, chosen by {@code endpoint}'s address type), sends a single {@link
 * FabricFrame.InvokeRequest}, and blocks for the matching response. Kept deliberately simple -- one
 * connection per call rather than a pooled/multiplexed client -- matching this project's MVP-first
 * convention; virtual threads make the per-call connection/thread cost cheap on both ends (a
 * synchronous proxy call is exactly one virtual thread blocked on exactly one connection).
 */
public final class FabricClient {

  private FabricClient() {}

  public static FabricFrame call(SocketAddress endpoint, FabricFrame.InvokeRequest request)
      throws IOException {
    boolean unixDomain = endpoint instanceof UnixDomainSocketAddress;
    try (SocketChannel channel =
        unixDomain ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open()) {
      channel.connect(endpoint);
      FabricCodec.write(Channels.newOutputStream(channel), request);
      FabricFrame response = FabricCodec.read(Channels.newInputStream(channel));
      if (response == null) {
        throw new EOFException(
            "fabric endpoint " + endpoint + " closed the connection without responding");
      }
      return response;
    }
  }
}
