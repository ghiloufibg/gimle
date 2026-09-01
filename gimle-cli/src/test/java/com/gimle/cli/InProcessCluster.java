package com.gimle.cli;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.muninn.MuninnServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A real single-node store + Fafnir + control plane (and optionally a real Muninn behind it), all
 * in this JVM over loopback sockets -- the same "real server, not mocked" fixture shape {@code
 * GimleCliTest} builds inline, extracted here because the observability verbs need the same stack
 * plus Muninn.
 */
final class InProcessCluster implements AutoCloseable {

  private final RaftNode storeRaftNode;
  private final StoreTransport storeTransport;
  private final StoreClient storeClient;
  private final FafnirServer fafnirServer;
  private final FafnirClient fafnirClient;
  private final MuninnServer muninnServer;
  private final MuninnClient muninnClient;
  private final ApiServer apiServer;

  private InProcessCluster(Path root, boolean withMuninn) {
    try {
      StateStore store = new StateStore();
      RaftLog raftLog = new RaftLog(root.resolve("raft"));
      storeRaftNode = new RaftNode("self", Map.of(), raftLog, store);
      storeRaftNode.start();
      StoreNode storeNode = new StoreNode(storeRaftNode, store, Map.of());
      storeTransport = new StoreTransport(storeNode);
      SocketAddress storeAddress = storeTransport.listen(new InetSocketAddress("127.0.0.1", 0));
      storeClient = new StoreClient(List.of(storeAddress));

      FafnirCrypto crypto = new FafnirCrypto(storeClient, root.resolve("keys/secret.key"));
      fafnirServer = new FafnirServer(crypto, 0);
      fafnirServer.start();
      fafnirClient = new FafnirClient("localhost:" + fafnirServer.port());

      if (withMuninn) {
        muninnServer = new MuninnServer(storeClient, 0, root.resolve("muninn"));
        muninnServer.start();
        muninnClient = new MuninnClient("127.0.0.1:" + muninnServer.port());
        apiServer = new ApiServer(storeClient, 0, fafnirClient, muninnClient);
      } else {
        muninnServer = null;
        muninnClient = null;
        apiServer = new ApiServer(storeClient, 0, fafnirClient);
      }
      apiServer.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static InProcessCluster start(Path root) {
    return new InProcessCluster(root, false);
  }

  static InProcessCluster startWithMuninn(Path root) {
    return new InProcessCluster(root, true);
  }

  String address() {
    return "localhost:" + apiServer.port();
  }

  String muninnAddress() {
    return "127.0.0.1:" + muninnServer.port();
  }

  StoreClient storeClient() {
    return storeClient;
  }

  @Override
  public void close() {
    apiServer.close();
    if (muninnClient != null) {
      muninnClient.close();
    }
    if (muninnServer != null) {
      muninnServer.close();
    }
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
    storeRaftNode.close();
  }
}
