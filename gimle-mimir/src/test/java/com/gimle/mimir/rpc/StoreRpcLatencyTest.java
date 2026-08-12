package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.store.StateStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the framing/socket-option pairing that keeps store RPC at loopback latency rather than at
 * TCP's delayed-ACK timer.
 *
 * <p>The regression this exists for: {@code StoreCodec.write} used to emit the 4-byte length prefix
 * and the body as two separate writes on an unbuffered socket stream. Nagle then held the second
 * segment until the first was acknowledged, and the peer's delayed-ACK timer only fires after ~40ms
 * -- so every request <em>and</em> every response paid that, roughly 80ms per round trip on a link
 * with microsecond latency. It was invisible in every existing test, because each one made only a
 * handful of calls; it showed up the first time something made a few dozen in a row, where a single
 * control-plane reconcile pass took 1.8 seconds instead of 55 milliseconds.
 *
 * <p>Asserted as a budget over many calls rather than a per-call timing, so the bound sits two
 * orders of magnitude away from the failure mode in one direction and a comfortable margin from
 * ordinary scheduling noise in the other: {@value #CALLS} sequential round trips cost ~50ms with
 * the fix and would cost upwards of {@value #CALLS}*40ms without it.
 */
class StoreRpcLatencyTest {

  private static final int CALLS = 50;

  /**
   * Generous enough to absorb a slow CI machine, JIT warmup, and a GC pause, while still failing
   * outright on a per-call delayed-ACK stall -- which alone would cost at least 2 seconds here.
   */
  private static final Duration BUDGET = Duration.ofMillis(1500);

  @Test
  @Timeout(30)
  void many_sequential_store_reads_are_not_paying_a_per_call_nagle_stall(@TempDir Path tempDir)
      throws IOException {
    StateStore store = new StateStore(tempDir.resolve("store"));
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    RaftNode raftNode = new RaftNode("self", Map.of(), raftLog, store);
    raftNode.start();
    StoreNode storeNode = new StoreNode(raftNode, store, Map.of());

    try (StoreTransport transport = new StoreTransport(storeNode)) {
      SocketAddress address = transport.listen(new InetSocketAddress("127.0.0.1", 0));
      try (StoreClient client = new StoreClient(List.of(address))) {
        client.listDeployments(); // connect and warm up, outside the measured window

        long startNanos = System.nanoTime();
        for (int call = 0; call < CALLS; call++) {
          client.listDeployments();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertTrue(
            elapsed.compareTo(BUDGET) < 0,
            CALLS
                + " sequential store round trips took "
                + elapsed.toMillis()
                + "ms, over the "
                + BUDGET.toMillis()
                + "ms budget -- that is the signature of a per-call delayed-ACK stall, so check"
                + " that frames are written in one write (Frames.writeFrame) and that both socket"
                + " ends set TCP_NODELAY");
      }
    } finally {
      raftNode.close();
    }
  }
}
