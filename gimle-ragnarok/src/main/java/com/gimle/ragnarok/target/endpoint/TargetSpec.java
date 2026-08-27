package com.gimle.ragnarok.target.endpoint;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.inventory.InventorySpec;
import com.gimle.ragnarok.target.inventory.SshInventoryClusterTarget;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The raw, unopened configuration for a cluster target: base URLs and store endpoints exactly as an
 * operator wrote them, how to reach them (plaintext or mTLS), and -- when {@link #inventory} is
 * present -- the machines/managed-processes an {@link SshInventoryClusterTarget} controls over SSH
 * on top of that same network access. {@link EndpointClusterTarget}/{@link HttpControlPlaneClient}
 * are themselves TLS-agnostic -- both just take a pre-built {@link HttpClient} -- so {@link
 * #open()} is the one place that turns this declarative spec into a live target, the same split
 * {@link com.gimle.holmgang.cluster.GimleCluster}'s own {@code buildOperatorClient()} already draws
 * between "what to connect to" and "the client that connects". The presence of {@link #inventory}
 * is the only discriminator between the two target kinds -- no separate {@code kind:} field.
 */
public record TargetSpec(
    List<String> controlPlaneBaseUrls,
    List<String> storeClientEndpoints,
    List<String> muninnBaseUrls,
    List<String> andvariBaseUrls,
    TransportProtocol transport,
    Optional<TlsSettings> tls,
    Optional<InventorySpec> inventory,
    Path workDir) {

  public TargetSpec {
    controlPlaneBaseUrls = List.copyOf(controlPlaneBaseUrls);
    storeClientEndpoints = List.copyOf(storeClientEndpoints);
    muninnBaseUrls = List.copyOf(muninnBaseUrls);
    andvariBaseUrls = List.copyOf(andvariBaseUrls);
    if (controlPlaneBaseUrls.isEmpty()) {
      throw new RagnarokException("a target must declare at least one control-plane base URL");
    }
    if (transport == TransportProtocol.TLS && tls.isEmpty()) {
      throw new RagnarokException(
          "transport: tls requires a tls: {certFile, keyFile, caFile} block");
    }
  }

  /** Builds the {@link HttpClient} and constructs the live target -- endpoint-only or inventory. */
  public ClusterTarget open() {
    final HttpClient httpClient =
        transport == TransportProtocol.TLS
            ? HttpClient.newBuilder()
                .sslContext(SslContexts.forMutualTls(tls.orElseThrow()))
                .build()
            : HttpClient.newHttpClient();
    final List<SocketAddress> storeEndpoints =
        storeClientEndpoints.stream().map(TargetSpec::parseHostPort).toList();
    if (inventory.isPresent()) {
      return new SshInventoryClusterTarget(
          controlPlaneBaseUrls,
          httpClient,
          storeEndpoints,
          muninnBaseUrls,
          andvariBaseUrls,
          workDir,
          inventory.get());
    }
    return new EndpointClusterTarget(
        controlPlaneBaseUrls, httpClient, storeEndpoints, muninnBaseUrls, andvariBaseUrls, workDir);
  }

  private static SocketAddress parseHostPort(final String hostPort) {
    final int colon = hostPort.lastIndexOf(':');
    if (colon <= 0 || colon == hostPort.length() - 1) {
      throw new RagnarokException("store client endpoint must be 'host:port', got: " + hostPort);
    }
    final String host = hostPort.substring(0, colon);
    final int port;
    try {
      port = Integer.parseInt(hostPort.substring(colon + 1));
    } catch (final NumberFormatException e) {
      throw new RagnarokException("store client endpoint must be 'host:port', got: " + hostPort, e);
    }
    return new InetSocketAddress(host, port);
  }
}
