package com.gimle.hilmir.store;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.hilmir.HilmirException;
import com.gimle.mimir.rpc.StoreClient;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.util.List;

/**
 * {@code hilmir store remove <peerId> (--topology <file> | --server <host:clientPort>[,...])
 * [--pki-dir <dir>]}: the symmetric removal counterpart to {@link StoreAddCommand}, over {@code
 * StoreClient#removeServer}.
 */
public final class StoreRemoveCommand {

  private static final String USAGE =
      """
      usage: hilmir store remove <peerId>
          (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
          [--pki-dir <dir>]""";

  private StoreRemoveCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    if (args.isEmpty()) {
      throw new HilmirException(USAGE);
    }
    final String peerId = args.get(0);
    final List<SocketAddress> endpoints = StoreEndpoints.resolve(args.subList(1, args.size()));

    try (StoreClient client = new StoreClient(endpoints)) {
      StoreRetry.run("remove of store peer " + peerId, out, () -> client.removeServer(peerId));
    } catch (final GimleRaftException e) {
      throw new HilmirException("failed to remove store peer " + peerId + ": " + e.getMessage(), e);
    }
    out.println("removed store peer " + peerId);
    return 0;
  }
}
