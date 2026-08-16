package com.gimle.hilmir.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirMain;
import com.gimle.hilmir.store.testsupport.AddressableStoreNode;
import com.gimle.mimir.rpc.StoreClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code hilmir store add}/{@code hilmir store remove} against a real, addressable single-node
 * {@link AddressableStoreNode} cluster over real loopback TCP and the {@code --server} endpoint
 * path -- the {@code --topology} path's own endpoint derivation is covered without a live store by
 * {@link StoreEndpointsTest}.
 */
class StoreCommandsClusterTest {

  @TempDir Path tempDir;

  private record Result(int exitCode, String out, String err) {}

  private static Result run(final String... args) {
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final int exitCode =
        HilmirMain.run(
            args,
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new Result(
        exitCode,
        outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }

  private static String spec(final InetSocketAddress address) {
    return address.getHostString() + ":" + address.getPort();
  }

  @Test
  @Timeout(20)
  void add_joins_a_real_peer_and_it_becomes_a_visible_cluster_member() throws Exception {
    try (AddressableStoreNode leader =
            AddressableStoreNode.startLeader("127.0.0.1", tempDir.resolve("leader"));
        AddressableStoreNode joiner =
            AddressableStoreNode.buildUnstarted("127.0.0.1", tempDir.resolve("joiner"))) {
      final String peerId = joiner.raftId();

      final Result result =
          run(
              "store",
              "add",
              peerId,
              "127.0.0.1",
              String.valueOf(joiner.peerAddress().raftPort()),
              String.valueOf(joiner.peerAddress().clientPort()),
              "--server",
              spec((InetSocketAddress) leader.clientAddress()));

      assertEquals(0, result.exitCode(), result.err());
      assertTrue(result.out().contains("added store peer " + peerId));

      try (StoreClient verify = new StoreClient(List.of(leader.clientAddress()))) {
        assertTrue(
            verify.status().memberIds().contains(peerId), verify.status().memberIds()::toString);
      }
    }
  }

  @Test
  @Timeout(20)
  void remove_drops_a_previously_added_peer_from_the_membership() throws Exception {
    try (AddressableStoreNode leader =
            AddressableStoreNode.startLeader("127.0.0.1", tempDir.resolve("leader"));
        AddressableStoreNode joiner =
            AddressableStoreNode.buildUnstarted("127.0.0.1", tempDir.resolve("joiner"))) {
      final String peerId = joiner.raftId();
      final String serverSpec = spec((InetSocketAddress) leader.clientAddress());

      final Result addResult =
          run(
              "store",
              "add",
              peerId,
              "127.0.0.1",
              String.valueOf(joiner.peerAddress().raftPort()),
              String.valueOf(joiner.peerAddress().clientPort()),
              "--server",
              serverSpec);
      assertEquals(0, addResult.exitCode(), addResult.err());

      final Result removeResult = run("store", "remove", peerId, "--server", serverSpec);

      assertEquals(0, removeResult.exitCode(), removeResult.err());
      assertTrue(removeResult.out().contains("removed store peer " + peerId));

      try (StoreClient verify = new StoreClient(List.of(leader.clientAddress()))) {
        assertFalse(
            verify.status().memberIds().contains(peerId), verify.status().memberIds()::toString);
      }
    }
  }

  @Test
  @Timeout(10)
  void remove_of_a_never_added_peer_fails_fast_with_a_clean_error() throws Exception {
    try (AddressableStoreNode leader =
        AddressableStoreNode.startLeader("127.0.0.1", tempDir.resolve("leader"))) {
      final long startNanos = System.nanoTime();

      final Result result =
          run(
              "store",
              "remove",
              "127.0.0.1:59999",
              "--server",
              spec((InetSocketAddress) leader.clientAddress()));

      final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      assertEquals(1, result.exitCode());
      assertTrue(result.err().startsWith("error: "), result.err());
      assertTrue(result.err().contains("127.0.0.1:59999"), result.err());
      // The bounded retry loop (10 attempts, 200ms apart) must give up well short of a minute --
      // no hang, no unbounded retry.
      assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, "took " + elapsed);
    }
  }
}
