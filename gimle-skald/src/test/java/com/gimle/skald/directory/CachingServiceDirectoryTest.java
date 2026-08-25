package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CachingServiceDirectoryTest {

  @Test
  void resolves_unknown_names_to_empty() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    assertTrue(directory.resolveAll("orders").isEmpty());
  }

  @Test
  void resolves_every_endpoint_of_a_known_name() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(
        Map.of(
            "orders",
            List.of(
                new HostPort("10.0.0.5", 8080),
                new HostPort("10.0.0.6", 8080),
                new HostPort("10.0.0.7", 9090))));

    assertEquals(
        List.of(
            new HostPort("10.0.0.5", 8080),
            new HostPort("10.0.0.6", 8080),
            new HostPort("10.0.0.7", 9090)),
        directory.resolveAll("orders"));
  }

  @Test
  void a_refresh_that_drops_a_name_makes_it_unresolvable_again() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));
    assertTrue(!directory.resolveAll("orders").isEmpty());

    directory.replaceAll(Map.of());

    assertTrue(directory.resolveAll("orders").isEmpty());
  }
}
