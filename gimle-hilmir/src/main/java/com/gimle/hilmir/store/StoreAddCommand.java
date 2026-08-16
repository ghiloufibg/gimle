package com.gimle.hilmir.store;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.hilmir.HilmirException;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.rpc.StoreClient;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.util.List;

/**
 * {@code hilmir store add <peerId> <host> <raftPort> <clientPort> (--topology <file> | --server
 * <host:clientPort>[,...]) [--pki-dir <dir>]}: the operator-facing surface over {@code
 * StoreClient#addServer}, the only caller of that method previously being test fixtures ({@code
 * gimle-smoke-tests}' {@code RaftResilienceIT}, {@code gimle-holmgang}'s {@code GimleCluster}).
 *
 * <p>{@code peerId} is taken as an explicit positional argument, the same {@code host:raftPort}
 * convention {@link PeerAddress#raftId()} already establishes, rather than derived from {@code
 * host} and {@code raftPort} here -- keeping it explicit matches how every existing real caller
 * already supplies it independently.
 */
public final class StoreAddCommand {

  private static final String USAGE =
      """
      usage: hilmir store add <peerId> <host> <raftPort> <clientPort>
          (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
          [--pki-dir <dir>]""";

  private StoreAddCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    if (args.size() < 4) {
      throw new HilmirException(USAGE);
    }
    final String peerId = args.get(0);
    final String host = args.get(1);
    final int raftPort = parsePort(args.get(2), "raftPort");
    final int clientPort = parsePort(args.get(3), "clientPort");
    final PeerAddress address = new PeerAddress(host, raftPort, clientPort);
    final List<SocketAddress> endpoints = StoreEndpoints.resolve(args.subList(4, args.size()));

    try (StoreClient client = new StoreClient(endpoints)) {
      StoreRetry.run("add of store peer " + peerId, out, () -> client.addServer(peerId, address));
    } catch (final GimleRaftException e) {
      throw new HilmirException("failed to add store peer " + peerId + ": " + e.getMessage(), e);
    }
    out.println(
        "added store peer " + peerId + " (" + host + ":" + raftPort + ":" + clientPort + ")");
    return 0;
  }

  private static int parsePort(final String value, final String name) {
    try {
      return Integer.parseInt(value);
    } catch (final NumberFormatException e) {
      throw new HilmirException("invalid " + name + ": '" + value + "'");
    }
  }
}
