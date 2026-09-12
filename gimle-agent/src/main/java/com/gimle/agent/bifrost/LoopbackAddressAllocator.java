package com.gimle.agent.bifrost;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Synthesizes a stable per-service "ClusterIP" in the {@code 127.0.0.0/8} loopback block -- binding
 * an address there needs no elevated privilege on Linux, unlike binding a non-loopback alias would.
 * The last two octets are derived from {@link String#hashCode()}, whose algorithm is part of the
 * {@code String} contract and therefore stable across JVM runs and restarts: the same service name
 * always resolves to the same address (when nothing else currently bound at that address's own
 * declared port forces a reassignment -- see {@link #allocate}), so a caller that resolved it once
 * can keep dialing it for as long as the service exists, without asking {@link BifrostProxy} again.
 */
final class LoopbackAddressAllocator {

  /** {@code x}/{@code y} each drawn from {@code 1..254} -- see {@link #allocate}'s own javadoc. */
  private static final int OCTET_RANGE = 254;

  private static final int SLOT_COUNT = OCTET_RANGE * OCTET_RANGE;

  private LoopbackAddressAllocator() {}

  /**
   * Maps {@code serviceName} to a {@code 127.x.y.1} address not already reserved at {@code port},
   * with {@code x} and {@code y} each drawn from the range {@code 1..254} -- never {@code 0}
   * (network-style meaning at the start of an octet) or {@code 255} (broadcast-style meaning at the
   * end), even though neither carries that reserved meaning for an individual loopback address in
   * practice; avoiding them costs nothing and sidesteps ever having to reason about whether it
   * matters.
   *
   * <p>{@code serviceName}'s hash picks the starting slot, tried first -- the common case, and the
   * only case before this method took a {@code reserved} set, so an unrelated service's address
   * does not shift merely because some other, later-processed service exists. Only when that slot's
   * {@code (address, port)} pair is already reserved -- two service names whose hashes collide, and
   * which also happen to declare the same port, since two different ports at the same address never
   * actually conflict at the socket level -- does this linearly probe forward through the remaining
   * {@value #SLOT_COUNT}-slot space for the first unreserved one. {@link BifrostProxy} treats every
   * currently-live listener's own bound address as reserved before computing a new one, so this
   * never hands out an address a prior call in the same poll tick already claimed.
   */
  static InetAddress allocate(String serviceName, int port, Set<InetSocketAddress> reserved) {
    int startSlot = (serviceName.hashCode() & 0x7fffffff) % SLOT_COUNT;
    for (int i = 0; i < SLOT_COUNT; i++) {
      int slot = (startSlot + i) % SLOT_COUNT;
      int x = 1 + (slot % OCTET_RANGE);
      int y = 1 + (slot / OCTET_RANGE);
      InetAddress candidate = addressFor(x, y);
      if (!reserved.contains(new InetSocketAddress(candidate, port))) {
        return candidate;
      }
    }
    // Every one of the 64,516 addresses this allocator can ever produce is already bound to this
    // exact port -- unreachable at any realistic per-node service count, but a clear, typed
    // failure beats an infinite loop or a silently reused address if it somehow ever were.
    throw new IllegalStateException(
        "no loopback address available for service '"
            + serviceName
            + "' at port "
            + port
            + " -- all "
            + SLOT_COUNT
            + " addresses already reserved at that port");
  }

  private static InetAddress addressFor(int x, int y) {
    try {
      return InetAddress.getByAddress(new byte[] {127, (byte) x, (byte) y, 1});
    } catch (UnknownHostException e) {
      // getByAddress only ever validates the array length (4 or 16 bytes); a literal 4-byte
      // array can never fail that check, so this branch is unreachable.
      throw new IllegalStateException(e);
    }
  }
}
