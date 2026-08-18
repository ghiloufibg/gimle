package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class LoopbackAddressAllocatorTest {

  @Test
  void allocates_the_same_address_for_the_same_service_name_every_time() {
    InetAddress first = LoopbackAddressAllocator.allocate("orders");
    InetAddress second = LoopbackAddressAllocator.allocate("orders");

    assertEquals(first, second);
  }

  @Test
  void allocates_different_addresses_for_different_service_names() {
    InetAddress orders = LoopbackAddressAllocator.allocate("orders");
    InetAddress payments = LoopbackAddressAllocator.allocate("payments");

    assertNotEquals(orders, payments);
  }

  @Test
  void allocates_within_the_127_0_0_0_8_loopback_block_with_the_final_octet_fixed_at_1() {
    byte[] address = LoopbackAddressAllocator.allocate("greeter-provider").getAddress();

    assertEquals(127, Byte.toUnsignedInt(address[0]));
    assertEquals(1, Byte.toUnsignedInt(address[3]));
  }
}
