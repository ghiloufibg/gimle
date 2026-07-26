package com.gimle.fabric.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class LeastOutstandingRequestsSelectorTest {

  @Test
  void selects_the_candidate_with_fewest_outstanding_requests() {
    LeastOutstandingRequestsSelector<String> selector = new LeastOutstandingRequestsSelector<>();
    List<String> candidates = List.of("a", "b", "c");
    selector.begin("a");
    selector.begin("a");
    selector.begin("b");

    assertEquals("c", selector.select(candidates));
  }

  @Test
  void ties_are_broken_round_robin() {
    LeastOutstandingRequestsSelector<String> selector = new LeastOutstandingRequestsSelector<>();
    List<String> candidates = List.of("a", "b");

    assertEquals("a", selector.select(candidates));
    assertEquals("b", selector.select(candidates));
    assertEquals("a", selector.select(candidates));
  }

  @Test
  void end_decrements_the_outstanding_count() {
    LeastOutstandingRequestsSelector<String> selector = new LeastOutstandingRequestsSelector<>();
    List<String> candidates = List.of("a", "b");
    selector.begin("a");
    selector.begin("a");
    selector.begin("b");
    assertEquals("b", selector.select(candidates), "a=2, b=1: b has fewer outstanding");

    selector.end("a");
    selector.end("a");
    assertEquals("a", selector.select(candidates), "a's outstanding count is back down to 0");
  }

  @Test
  void end_never_goes_negative() {
    LeastOutstandingRequestsSelector<String> selector = new LeastOutstandingRequestsSelector<>();
    selector.end("never-begun");
    selector.end("never-begun");
    List<String> candidates = List.of("never-begun", "other");
    // both start at outstanding=0; round-robin picks the first candidate first.
    assertEquals("never-begun", selector.select(candidates));
  }

  @Test
  void empty_candidate_list_throws() {
    LeastOutstandingRequestsSelector<String> selector = new LeastOutstandingRequestsSelector<>();
    assertThrows(NoSuchElementException.class, () -> selector.select(List.of()));
  }
}
