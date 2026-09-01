package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CachingServiceDirectoryTest {

  @Test
  void resolves_unknown_names_to_an_absent_optional() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    assertEquals(Optional.empty(), directory.resolveAll("orders"));
  }

  @Test
  void a_known_name_with_no_endpoints_resolves_to_a_present_but_empty_list() {
    // The distinction the whole Optional exists for: this Service was in the catalog, it simply
    // has no live instance right now, which is not the same answer as "no such Service."
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of()));

    assertEquals(Optional.of(List.of()), directory.resolveAll("orders"));
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
        directory.resolveAll("orders").orElseThrow());
  }

  @Test
  void a_refresh_that_drops_a_name_makes_it_unresolvable_again() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));
    assertTrue(directory.resolveAll("orders").isPresent());

    directory.replaceAll(Map.of());

    assertEquals(Optional.empty(), directory.resolveAll("orders"));
  }

  @Test
  void a_refresh_that_empties_a_name_keeps_it_known_rather_than_dropping_it() {
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));

    directory.replaceAll(Map.of("orders", List.of()));

    assertEquals(Optional.of(List.of()), directory.resolveAll("orders"));
  }

  @Test
  void a_successful_refresh_resets_the_last_success_time_and_the_failure_count() {
    TestClock clock = new TestClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    directory.recordPollFailure();
    directory.recordPollFailure();
    assertEquals(2, directory.consecutiveFailures());

    clock.advance(Duration.ofSeconds(30));
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));

    assertEquals(0, directory.consecutiveFailures());
    assertEquals(Duration.ZERO, directory.timeSinceLastSuccess());
  }

  @Test
  void a_poll_failure_leaves_the_cached_data_intact_but_grows_staleness_and_failure_count() {
    TestClock clock = new TestClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));

    clock.advance(Duration.ofSeconds(10));
    directory.recordPollFailure();

    // the pre-existing, still-correct data must survive a failed poll untouched
    assertEquals(
        List.of(new HostPort("10.0.0.5", 8080)), directory.resolveAll("orders").orElseThrow());
    assertEquals(1, directory.consecutiveFailures());
    assertEquals(Duration.ofSeconds(10), directory.timeSinceLastSuccess());
  }

  @Test
  void staleness_accrues_from_construction_when_no_poll_has_ever_succeeded() {
    TestClock clock = new TestClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);

    clock.advance(Duration.ofSeconds(45));

    assertEquals(Duration.ofSeconds(45), directory.timeSinceLastSuccess());
  }
}
