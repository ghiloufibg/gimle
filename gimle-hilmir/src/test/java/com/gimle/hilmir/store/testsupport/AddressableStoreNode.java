package com.gimle.hilmir.store.testsupport;

import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.PeerConnection;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClientFactory;
import com.gimle.mimir.raft.RaftTransport;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real, single-node {@code RaftNode}/{@code StoreNode} wired over real loopback TCP -- built
 * through the address-aware {@link RaftNode} constructor so it supports live membership changes,
 * the same wiring {@code com.gimle.mimir.StoreMain#main} does for a real store process, minus
 * TLS/cert-rotation/tracing/Muninn-shipping, none of which any {@code hilmir store} test needs.
 *
 * <p>Every existing cross-module {@code InProcessStore} test fixture ({@code gimle-controlplane},
 * {@code gimle-fafnir}, {@code gimle-muninn}, {@code gimle-andvari}) builds its {@code RaftNode}
 * via the legacy, address-less constructor, which explicitly does not support {@code
 * addServer}/{@code removeServer} -- exercising those from {@code gimle-hilmir} needs this
 * module-local equivalent instead.
 */
public final class AddressableStoreNode implements AutoCloseable {

  private final RaftNode raftNode;
  private final RaftTransport raftTransport;
  private final StoreTransport storeTransport;
  private final PeerAddress selfAddress;
  private final String raftId;

  private AddressableStoreNode(
      final RaftNode raftNode,
      final RaftTransport raftTransport,
      final StoreTransport storeTransport,
      final PeerAddress selfAddress,
      final String raftId) {
    this.raftNode = raftNode;
    this.raftTransport = raftTransport;
    this.storeTransport = storeTransport;
    this.selfAddress = selfAddress;
    this.raftId = raftId;
  }

  /** Starts as the sole member of its own single-node cluster -- becomes leader immediately. */
  public static AddressableStoreNode startLeader(final String host, final Path stateDir)
      throws IOException {
    final AddressableStoreNode node = build(host, stateDir);
    node.raftNode.start();
    return node;
  }

  /**
   * Built and reachable over real sockets, but {@link RaftNode#start} deliberately never called --
   * exactly {@code RaftClusterTest#addNewNode}'s own posture: a fresh single-node {@code start()}
   * would elect it leader of its own one-node "cluster" immediately, wrong for a node about to join
   * an existing cluster as a learner. Its RPC handlers work regardless of {@code start()}, so it
   * still correctly catches up once a real leader's {@code addServer} begins replicating to it.
   */
  public static AddressableStoreNode buildUnstarted(final String host, final Path stateDir)
      throws IOException {
    return build(host, stateDir);
  }

  private static AddressableStoreNode build(final String host, final Path stateDir)
      throws IOException {
    final InetSocketAddress raftBind = reserveAddress(host);
    final InetSocketAddress clientBind = reserveAddress(host);
    final String raftId = host + ":" + raftBind.getPort();
    final PeerAddress selfAddress = new PeerAddress(host, raftBind.getPort(), clientBind.getPort());

    final Map<String, String> raftIdToClientAddress = new ConcurrentHashMap<>();
    raftIdToClientAddress.put(raftId, host + ":" + clientBind.getPort());

    final RaftPeerClientFactory peerClientFactory =
        address -> new PeerConnection(new InetSocketAddress(address.host(), address.raftPort()));

    final StateStore store = new StateStore();
    final RaftLog raftLog = new RaftLog(stateDir.resolve("raft"));
    final RaftNode raftNode =
        new RaftNode(
            raftId,
            selfAddress,
            new LinkedHashMap<>(),
            peerClientFactory,
            raftLog,
            store,
            peers ->
                peers.forEach(
                    (peerId, address) ->
                        raftIdToClientAddress.put(peerId, address.clientAddress())));

    final RaftTransport raftTransport = new RaftTransport(raftNode);
    raftTransport.listen(raftBind);

    final StoreNode storeNode = new StoreNode(raftNode, store, raftIdToClientAddress);
    final StoreTransport storeTransport = new StoreTransport(storeNode);
    storeTransport.listen(clientBind);

    return new AddressableStoreNode(raftNode, raftTransport, storeTransport, selfAddress, raftId);
  }

  /** A free ephemeral port on {@code host}, reserved by briefly binding and releasing it. */
  private static InetSocketAddress reserveAddress(final String host) throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress(host, 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  public SocketAddress clientAddress() {
    return new InetSocketAddress(selfAddress.host(), selfAddress.clientPort());
  }

  public PeerAddress peerAddress() {
    return selfAddress;
  }

  public String raftId() {
    return raftId;
  }

  @Override
  public void close() {
    storeTransport.close();
    raftTransport.close();
    raftNode.close();
  }
}
