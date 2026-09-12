package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LoopbackAddressAllocatorTest {

  private static final int PORT = 8080;

  @Test
  void allocates_the_same_address_for_the_same_service_name_every_time() {
    InetAddress first = LoopbackAddressAllocator.allocate("orders", PORT, Set.of());
    InetAddress second = LoopbackAddressAllocator.allocate("orders", PORT, Set.of());

    assertEquals(first, second);
  }

  @Test
  void allocates_different_addresses_for_different_service_names() {
    InetAddress orders = LoopbackAddressAllocator.allocate("orders", PORT, Set.of());
    InetAddress payments = LoopbackAddressAllocator.allocate("payments", PORT, Set.of());

    assertNotEquals(orders, payments);
  }

  @Test
  void allocates_within_the_127_0_0_0_8_loopback_block_with_the_final_octet_fixed_at_1() {
    byte[] address =
        LoopbackAddressAllocator.allocate("greeter-provider", PORT, Set.of()).getAddress();

    assertEquals(127, Byte.toUnsignedInt(address[0]));
    assertEquals(1, Byte.toUnsignedInt(address[3]));
  }

  /**
   * The bug this proves fixed: with no reserved set to consult, "3m88yv76" and "vr7pe6ya" hash to
   * the identical address (found by brute force -- see the Round 6 report) -- previously, whichever
   * of the two this proxy tried to bind second would fail with a real BindException and retry-fail
   * forever. Reserving the first one's address before allocating the second's proves the allocator
   * itself now routes around a colliding hash instead of handing out the same address twice.
   */
  @Test
  void a_hash_collision_at_the_same_port_is_resolved_to_a_distinct_address() {
    InetAddress first = LoopbackAddressAllocator.allocate("3m88yv76", PORT, Set.of());
    InetAddress second = LoopbackAddressAllocator.allocate("vr7pe6ya", PORT, Set.of());
    assertEquals(first, second, "test fixture assumption: these two names must actually collide");

    InetAddress reassigned =
        LoopbackAddressAllocator.allocate(
            "vr7pe6ya", PORT, Set.of(new InetSocketAddress(first, PORT)));

    assertNotEquals(first, reassigned);
  }

  /**
   * Two service names whose hashes collide never actually conflict at the socket level if they
   * declare different ports -- the reservation is keyed on (address, port), not address alone, so
   * this must not force a reassignment neither service asked for.
   */
  @Test
  void a_hash_collision_at_different_ports_does_not_force_a_reassignment() {
    InetAddress first = LoopbackAddressAllocator.allocate("3m88yv76", PORT, Set.of());

    InetAddress second =
        LoopbackAddressAllocator.allocate(
            "vr7pe6ya", 9090, Set.of(new InetSocketAddress(first, PORT)));

    assertEquals(first, second);
  }

  @Test
  void every_address_already_reserved_at_the_port_is_a_clean_failure_not_an_infinite_loop() {
    Set<InetSocketAddress> everyAddressAtThisPort =
        java.util.stream.IntStream.rangeClosed(1, 254)
            .boxed()
            .flatMap(
                x ->
                    java.util.stream.IntStream.rangeClosed(1, 254)
                        .mapToObj(
                            y -> {
                              try {
                                return new InetSocketAddress(
                                    InetAddress.getByAddress(
                                        new byte[] {127, (byte) (int) x, (byte) y, 1}),
                                    PORT);
                              } catch (java.net.UnknownHostException e) {
                                throw new AssertionError(e);
                              }
                            }))
            .collect(java.util.stream.Collectors.toSet());

    assertThrows(
        IllegalStateException.class,
        () -> LoopbackAddressAllocator.allocate("orders", PORT, everyAddressAtThisPort));
  }
}
