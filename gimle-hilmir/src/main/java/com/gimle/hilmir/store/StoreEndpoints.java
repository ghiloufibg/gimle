package com.gimle.hilmir.store;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.topology.Transport;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the bootstrap store endpoint list a {@code store add}/{@code store remove} invocation
 * connects through, from either {@code --topology <file>} or {@code --server
 * <host:clientPort>[,<host:clientPort>...]}, and activates mTLS system properties as a side effect
 * when the resolved source calls for it -- {@code StoreConnection} reads {@code
 * gimle.transport.protocol}/{@code gimle.tls.*} lazily per socket, so this must run before the
 * caller constructs its {@code StoreClient}, never after.
 *
 * <p>Deliberately a small local equivalent of {@code com.gimle.hilmir.launch.TopologyAddresses}'s
 * host-resolution lookup rather than a reuse of it: that class is package-private to {@code
 * com.gimle.hilmir.launch}, a package outside this change's scope.
 */
final class StoreEndpoints {

  private StoreEndpoints() {}

  static List<SocketAddress> resolve(final List<String> flags) {
    final Optional<String> topologyFile = flagValue(flags, "--topology");
    final Optional<String> serverSpec = flagValue(flags, "--server");
    if (topologyFile.isPresent() == serverSpec.isPresent()) {
      throw new HilmirException(
          "exactly one of --topology <file> or --server <host:clientPort>[,<host:clientPort>...] "
              + "is required");
    }
    if (topologyFile.isPresent()) {
      return resolveFromTopology(Path.of(topologyFile.get()));
    }
    flagValue(flags, "--pki-dir").ifPresent(dir -> activateTls(Path.of(dir)));
    return parseServerSpec(serverSpec.get());
  }

  private static List<SocketAddress> resolveFromTopology(final Path file) {
    final Topology topology;
    try (InputStream in = Files.newInputStream(file)) {
      topology = TopologyParser.parse(in);
    } catch (final IOException e) {
      throw new HilmirException("failed reading topology file " + file + ": " + e.getMessage(), e);
    }
    if (topology.transport() == Transport.MTLS) {
      activateTls(
          topology
              .tls()
              .orElseThrow(() -> new HilmirException("mtls transport requires tls.materialDir"))
              .materialDir());
    }
    final List<StoreReplica> replicas = topology.store().replicas();
    if (replicas.isEmpty()) {
      throw new HilmirException("topology declares no store replicas");
    }
    final List<SocketAddress> endpoints = new ArrayList<>();
    for (final StoreReplica replica : replicas) {
      endpoints.add(
          new InetSocketAddress(hostOf(topology, replica.machine()), replica.clientPort()));
    }
    return endpoints;
  }

  private static String hostOf(final Topology topology, final String machineName) {
    return topology.machines().stream()
        .filter(m -> m.name().equals(machineName))
        .map(Machine::host)
        .findFirst()
        .orElseThrow(
            () -> new HilmirException("no machine named '" + machineName + "' in topology"));
  }

  private static List<SocketAddress> parseServerSpec(final String spec) {
    if (spec.isBlank()) {
      throw new HilmirException("--server must name at least one host:clientPort endpoint");
    }
    final List<SocketAddress> endpoints = new ArrayList<>();
    for (final String entry : spec.split(",")) {
      final String trimmed = entry.trim();
      final int colon = trimmed.lastIndexOf(':');
      if (colon < 0) {
        throw new HilmirException(
            "malformed --server entry (expected host:clientPort): " + trimmed);
      }
      final String host = trimmed.substring(0, colon);
      final int port;
      try {
        port = Integer.parseInt(trimmed.substring(colon + 1));
      } catch (final NumberFormatException e) {
        throw new HilmirException(
            "malformed --server entry (expected host:clientPort): " + trimmed);
      }
      endpoints.add(new InetSocketAddress(host, port));
    }
    return endpoints;
  }

  /**
   * The same three-file operator-identity convention {@code MachineLauncher}/{@code LaunchPlanner}
   * already use ({@code operator.crt}/{@code operator.key}/{@code ca.crt} under one material
   * directory) for every CLI operation that talks to an already-running cluster.
   */
  private static void activateTls(final Path materialDir) {
    System.setProperty("gimle.transport.protocol", "tls");
    System.setProperty("gimle.tls.certFile", materialDir.resolve("operator.crt").toString());
    System.setProperty("gimle.tls.keyFile", materialDir.resolve("operator.key").toString());
    System.setProperty("gimle.tls.caFile", materialDir.resolve("ca.crt").toString());
  }

  private static Optional<String> flagValue(final List<String> args, final String flag) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals(flag) && i + 1 < args.size()) {
        return Optional.of(args.get(i + 1));
      }
    }
    return Optional.empty();
  }
}
