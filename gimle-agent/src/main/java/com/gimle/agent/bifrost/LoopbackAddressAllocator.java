package com.gimle.agent.bifrost;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Synthesizes a stable per-service "ClusterIP" in the {@code 127.0.0.0/8} loopback block -- binding
 * an address there needs no elevated privilege on Linux, unlike binding a non-loopback alias would.
 * The last two octets are derived from {@link String#hashCode()}, whose algorithm is part of the
 * {@code String} contract and therefore stable across JVM runs and restarts: the same service name
 * always resolves to the same address, so a caller that resolved it once can keep dialing it for as
 * long as the service exists, without asking {@link BifrostProxy} again.
 */
final class LoopbackAddressAllocator {

  private LoopbackAddressAllocator() {}

  /**
   * Maps {@code serviceName} to {@code 127.x.y.1}, with {@code x} and {@code y} each drawn from the
   * range {@code 1..254} -- never {@code 0} (network-style meaning at the start of an octet) or
   * {@code 255} (broadcast-style meaning at the end), even though neither carries that reserved
   * meaning for an individual loopback address in practice; avoiding them costs nothing and
   * sidesteps ever having to reason about whether it matters.
   */
  static InetAddress allocate(String serviceName) {
    int unsignedHash = serviceName.hashCode() & 0x7fffffff;
    int x = 1 + (unsignedHash % 254);
    int y = 1 + ((unsignedHash / 254) % 254);
    try {
      return InetAddress.getByAddress(new byte[] {127, (byte) x, (byte) y, 1});
    } catch (UnknownHostException e) {
      // getByAddress only ever validates the array length (4 or 16 bytes); a literal 4-byte
      // array can never fail that check, so this branch is unreachable.
      throw new IllegalStateException(e);
    }
  }
}
