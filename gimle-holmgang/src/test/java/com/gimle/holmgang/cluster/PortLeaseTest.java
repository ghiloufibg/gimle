package com.gimle.holmgang.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortLeaseTest {

  @Test
  void reserves_distinct_ports_and_holds_them_until_released() throws IOException {
    try (PortLease lease = PortLease.reserve(5)) {
      final List<Integer> ports = lease.ports();
      assertEquals(5, ports.size());
      assertEquals(5, new HashSet<>(ports).size());
      // Held: binding a reserved port must fail until it is released.
      final int first = ports.get(0);
      boolean bindFailed = false;
      try (ServerSocket ignored = new ServerSocket(first, 1, InetAddress.getLoopbackAddress())) {
        // Bound while still leased -- the lease is not actually holding the port.
      } catch (final IOException e) {
        bindFailed = true;
      }
      assertTrue(bindFailed, "a reserved port should not be bindable before release");
      lease.release(first);
      try (ServerSocket bound = new ServerSocket(first, 1, InetAddress.getLoopbackAddress())) {
        assertEquals(first, bound.getLocalPort());
      }
    }
  }
}
