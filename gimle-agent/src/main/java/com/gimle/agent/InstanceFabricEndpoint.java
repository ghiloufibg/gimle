package com.gimle.agent;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Where one supervised instance's worker JVM is reachable on the service fabric: the TCP address a
 * {@code FabricClient} dials to send it an {@code InvokeRequest}, plus the Unix domain socket path
 * a caller on this same machine would prefer instead.
 *
 * <p>The agent learns all of this from the worker's own {@code Hello} handshake and has, until now,
 * kept it to itself -- reachable only through SWIM gossip between agents, never through any
 * operator-facing surface. That made the address undiscoverable from outside the node, which in
 * turn made the fabric's own listener-side tenant re-check impossible to exercise against a real
 * cluster: proving that a call bypassing the caller-side filter is still rejected requires dialing
 * an instance directly, and nothing outside the owning agent could learn where to dial.
 *
 * <p>{@code tcpAddress} is absent for an instance the agent supervises but has not yet heard from
 * -- a distinct answer from "no such instance here", and reported as one, since it tells a caller
 * to retry rather than to look elsewhere. {@code workerId} rides the same handshake and is absent
 * for the same reason. {@code udsPath} is empty for a worker that bound no domain socket.
 */
record InstanceFabricEndpoint(
    Optional<String> workerId, Optional<InetSocketAddress> tcpAddress, String udsPath) {

  InstanceFabricEndpoint {
    if (workerId == null) {
      throw new IllegalArgumentException("workerId must not be null; use Optional.empty()");
    }
    if (tcpAddress == null) {
      throw new IllegalArgumentException("tcpAddress must not be null; use Optional.empty()");
    }
    if (udsPath == null) {
      throw new IllegalArgumentException("udsPath must not be null; use an empty string");
    }
  }

  /**
   * The address as a caller would write it, {@code host:port} -- taken from the resolved address
   * rather than the hostname, so what a client dials is what the worker actually bound.
   */
  static String text(final InetSocketAddress address) {
    return address.getAddress() == null
        ? address.getHostString() + ":" + address.getPort()
        : address.getAddress().getHostAddress() + ":" + address.getPort();
  }
}
