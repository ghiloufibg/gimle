package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CachingServiceDirectoryTest {

  @Test
  void resolves_unknown_names_to_empty() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    assertTrue(directory.resolveOne("orders").isEmpty());
  }

  @Test
  void resolves_a_single_endpoint_repeatedly() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of("10.0.0.5")));

    assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders"));
    assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders"));
  }

  @Test
  void round_robins_across_multiple_endpoints() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of("10.0.0.5", "10.0.0.6", "10.0.0.7")));

    assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders"));
    assertEquals(Optional.of("10.0.0.6"), directory.resolveOne("orders"));
    assertEquals(Optional.of("10.0.0.7"), directory.resolveOne("orders"));
    assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders")); // wraps around
  }

  @Test
  void a_refresh_that_drops_a_name_makes_it_unresolvable_again() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of("10.0.0.5")));
    assertTrue(directory.resolveOne("orders").isPresent());

    directory.replaceAll(Map.of());

    assertTrue(directory.resolveOne("orders").isEmpty());
  }
}
