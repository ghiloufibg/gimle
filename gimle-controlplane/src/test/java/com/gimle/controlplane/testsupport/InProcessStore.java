package com.gimle.controlplane.testsupport;

import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A single-node {@code gimle-mimir} store (real socket, real {@code RaftNode} that's trivially
 * always leader with an empty peer set) backing a ready {@link StoreClient} -- what every {@code
 * ApiServer}-wiring test used to get for free from a bare {@code StateStore} before the
 * etcd-store-extraction split moved that machinery into its own process. Replaces the old {@code
 * store = new StateStore(...); server = new ApiServer(store, 0);} pattern with {@code storeClient =
 * InProcessStore.start(...).client(); server = new ApiServer(storeClient, 0);} -- same "fast,
 * network-free-in-spirit, fresh per test" shape, just over a real (loopback) socket instead of a
 * bare in-memory object, since {@code ApiServer} no longer holds a {@code StateStore} reference at
 * all.
 */
public final class InProcessStore implements AutoCloseable {

  private final StateStore store;
  private final RaftNode raftNode;
  private final StoreTransport transport;
  private final StoreClient client;

  private InProcessStore(
      StateStore store, RaftNode raftNode, StoreTransport transport, StoreClient client) {
    this.store = store;
    this.raftNode = raftNode;
    this.transport = transport;
    this.client = client;
  }

  public static InProcessStore start(Path stateDir) throws IOException {
    StateStore store = new StateStore(stateDir.resolve("store"));
    RaftLog raftLog = new RaftLog(stateDir.resolve("raft"));
    RaftNode raftNode = new RaftNode("self", Map.of(), raftLog, store);
    raftNode.start(); // empty peer set: majority of one, trivially always leader
    StoreNode storeNode = new StoreNode(raftNode, store, Map.of());
    StoreTransport transport = new StoreTransport(storeNode);
    SocketAddress address = transport.listen(new InetSocketAddress("127.0.0.1", 0));
    StoreClient client = new StoreClient(List.of(address));
    return new InProcessStore(store, raftNode, transport, client);
  }

  public StoreClient client() {
    return client;
  }

  /**
   * The same underlying {@link StateStore} {@link #client()} talks to over the loopback socket --
   * for direct backdoor setup/assertions a test needs that go beyond {@code StoreClient}'s surface
   * (a direct {@code putXxx} bypassing {@code propose}, or a read {@code StoreRpc} was never given
   * a variant for), exactly the same shortcut these tests already took when they held a bare {@code
   * StateStore} directly, pre-split.
   */
  public StateStore store() {
    return store;
  }

  @Override
  public void close() {
    client.close();
    transport.close();
    raftNode.close();
  }
}
