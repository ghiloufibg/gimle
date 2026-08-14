package com.gimle.holmgang.cluster;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reserves a cluster's whole port budget up front by binding kernel-assigned loopback ports and
 * holding the listening sockets, so no topology ever declares a port and two clusters can coexist
 * on one machine. A port is {@link #release released} -- its socket closed -- immediately before
 * spawning the process that will bind it; the close-to-bind window is a real but tiny race, and
 * kernels hand out fresh ephemeral ports for new binds rather than immediately reusing a
 * just-closed one, so collisions are vanishingly rare rather than merely unlikely-by-convention.
 */
final class PortLease implements AutoCloseable {

  private final Map<Integer, ServerSocket> sockets = new LinkedHashMap<>();

  static PortLease reserve(final int count) {
    final PortLease lease = new PortLease();
    try {
      for (int i = 0; i < count; i++) {
        final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        lease.sockets.put(socket.getLocalPort(), socket);
      }
    } catch (final IOException e) {
      lease.close();
      throw new HolmgangException("failed reserving " + count + " loopback ports", e);
    }
    return lease;
  }

  List<Integer> ports() {
    return new ArrayList<>(sockets.keySet());
  }

  /** Closes the held socket for {@code port} so the process about to spawn can bind it. */
  void release(final int port) {
    final ServerSocket socket = sockets.remove(port);
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (final IOException e) {
      throw new HolmgangException("failed releasing reserved port " + port, e);
    }
  }

  @Override
  public void close() {
    for (final Integer port : List.copyOf(sockets.keySet())) {
      release(port);
    }
  }
}
