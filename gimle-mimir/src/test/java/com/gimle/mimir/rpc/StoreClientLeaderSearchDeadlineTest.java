package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Reproduces CHAOS-06: a node that stalls before answering {@link StoreRpc.NotLeader} (modeling
 * {@code RaftNode#awaitReadIndex} blocking a partitioned/just-deposed leader for up to its own
 * {@code proposeTimeout}) must not let one {@link StoreClient} call run for several multiples of
 * its own leader-search deadline. Each fake endpoint here is a real {@link StoreTransport} whose
 * handler sleeps before answering {@link StoreRpc.NotLeader} with a blank hint -- a genuine stall
 * on the wire, not a mocked method call -- so the only way this test passes is if the deadline is
 * actually enforced between individual endpoint attempts, not just between whole passes.
 */
class StoreClientLeaderSearchDeadlineTest {

  private final List<StoreTransport> transports = new ArrayList<>();
  private StoreClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    for (StoreTransport transport : transports) {
      transport.close();
    }
  }

  private SocketAddress stallingEndpoint(Duration stall) throws IOException {
    StoreTransport transport =
        new StoreTransport(
            request -> {
              try {
                Thread.sleep(stall.toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new StoreRpc.NotLeader("");
            });
    transports.add(transport);
    return transport.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  @Test
  @Timeout(30)
  void a_call_against_several_stalling_endpoints_does_not_run_for_many_multiples_of_the_deadline()
      throws Exception {
    Duration perEndpointStall = Duration.ofMillis(300);
    Duration leaderSearchTimeout = Duration.ofMillis(200);
    List<SocketAddress> endpoints = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      endpoints.add(stallingEndpoint(perEndpointStall));
    }
    client = new StoreClient(endpoints, leaderSearchTimeout);

    long start = System.nanoTime();
    assertThrows(GimleRaftException.class, () -> client.listTenants());
    long elapsed = System.nanoTime() - start;

    // Before the fix, one pass tries every one of the 5 stalling endpoints before the deadline is
    // ever rechecked -- roughly 5 * perEndpointStall (1500ms) for the first pass alone. With the
    // fix, the deadline is checked before each endpoint attempt, so the pass stops after the one
    // attempt already in flight when the deadline first passed -- at most a couple of stalls, never
    // anywhere close to trying all five.
    long threeStalls = perEndpointStall.multipliedBy(3).toNanos();
    assertTrue(
        elapsed < threeStalls,
        "expected the call to return within about one stall past the deadline, took "
            + Duration.ofNanos(elapsed)
            + " (5 full stalls would be "
            + perEndpointStall.multipliedBy(5)
            + ")");
  }

  /**
   * A single-endpoint sanity check isolating just the "does the outer deadline actually fire"
   * question from the multi-endpoint stop-early behavior above.
   */
  @Test
  @Timeout(15)
  void a_single_stalling_endpoint_fails_close_to_the_configured_deadline() throws Exception {
    Duration stall = Duration.ofSeconds(2);
    Duration leaderSearchTimeout = Duration.ofMillis(200);
    SocketAddress endpoint = stallingEndpoint(stall);
    client = new StoreClient(List.of(endpoint), leaderSearchTimeout);

    long start = System.nanoTime();
    assertThrows(GimleRaftException.class, () -> client.listTenants());
    long elapsed = System.nanoTime() - start;

    // The one in-flight attempt can't be preempted mid-call, so elapsed is bounded by the stall
    // itself, not by however many retry passes the outer loop might otherwise have attempted.
    assertTrue(
        elapsed < stall.plus(Duration.ofSeconds(1)).toNanos(),
        "expected roughly one stall's worth of time, took " + Duration.ofNanos(elapsed));
  }

  /** Guards against a trivially-fast false pass -- there must be at least one real attempt. */
  @Test
  @Timeout(15)
  void the_endpoint_is_actually_contacted_before_the_call_fails() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    StoreTransport transport =
        new StoreTransport(
            request -> {
              attempts.incrementAndGet();
              return new StoreRpc.NotLeader("");
            });
    transports.add(transport);
    SocketAddress endpoint = transport.listen(new InetSocketAddress("127.0.0.1", 0));
    client = new StoreClient(List.of(endpoint), Duration.ofMillis(200));

    assertThrows(GimleRaftException.class, () -> client.listTenants());

    assertTrue(attempts.get() > 0, "expected the fake endpoint to have been contacted");
  }
}
