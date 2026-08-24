package com.gimle.andvari.testsupport;

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
 * always leader with an empty peer set) backing a ready {@link StoreClient} -- the same fixture
 * shape {@code gimle-fafnir}'s and {@code gimle-muninn}'s own test trees already use (not shared
 * via a test-jar since none of these modules depends on another's test sources; small enough to
 * duplicate rather than introduce that coupling for).
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
    StateStore store = new StateStore();
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
