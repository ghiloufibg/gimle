package com.gimle.ragnarok.target.endpoint;

import com.gimle.mimir.rpc.StoreClient;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.ControlPlaneClient;
import com.gimle.ragnarok.target.GimleProcess;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.WorkerHandle;
import com.gimle.testkit.heimdall.Heimdall;
import com.gimle.testkit.heimdall.HeimdallScope;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A {@link ClusterTarget} reaching a real, already-running cluster over the network only -- HTTP
 * for the control plane, a direct {@link StoreClient} for the store's own read-only status RPC,
 * plain {@code GET .../status} for Muninn/Andvari. No process control and no boot-time network
 * interposition are possible here, so every process-handle accessor and {@link #faults()} always
 * report absent: Fenrir's own candidate-gathering already treats that as "nothing to strike" and
 * records the fault {@code SKIPPED} with a reason, never throws. The store-health gates ({@link
 * #storeLeaderId()}, {@link #storeMemberIds()}) still work for real, since they only need the
 * store's own client port, not process control over it.
 */
public final class EndpointClusterTarget implements ClusterTarget {

  private final List<String> controlPlaneBaseUrls;
  private final HttpClient httpClient;
  private final List<SocketAddress> storeClientEndpoints;
  private final List<String> muninnBaseUrls;
  private final List<String> andvariBaseUrls;
  private final Path workDir;
  private Heimdall heimdall;

  /**
   * @param controlPlaneBaseUrls one base URL (scheme://host:port) per control-plane replica
   * @param httpClient the client to reach every base URL with -- plaintext or already configured
   *     for mTLS with the operator's own certificate
   * @param storeClientEndpoints the store cluster's own client ports, for read-only status RPC;
   *     empty means the store-health gates degrade to "unknown" rather than failing outright
   * @param muninnBaseUrls one base URL per Muninn replica, empty if Muninn isn't in scope
   * @param andvariBaseUrls one base URL per Andvari replica, empty if Andvari isn't in scope
   * @param workDir where Heimdall's own forensic reports are written on a missed condition
   */
  public EndpointClusterTarget(
      final List<String> controlPlaneBaseUrls,
      final HttpClient httpClient,
      final List<SocketAddress> storeClientEndpoints,
      final List<String> muninnBaseUrls,
      final List<String> andvariBaseUrls,
      final Path workDir) {
    this.controlPlaneBaseUrls = List.copyOf(controlPlaneBaseUrls);
    this.httpClient = httpClient;
    this.storeClientEndpoints = List.copyOf(storeClientEndpoints);
    this.muninnBaseUrls = List.copyOf(muninnBaseUrls);
    this.andvariBaseUrls = List.copyOf(andvariBaseUrls);
    this.workDir = workDir;
  }

  @Override
  public List<String> controlPlaneBaseUrls() {
    return controlPlaneBaseUrls;
  }

  @Override
  public int controlPlaneCount() {
    return controlPlaneBaseUrls.size();
  }

  @Override
  public ControlPlaneClient api() {
    return api(0);
  }

  @Override
  public ControlPlaneClient api(final int controlPlaneIndex) {
    return new HttpControlPlaneClient(httpClient, controlPlaneBaseUrls.get(controlPlaneIndex));
  }

  @Override
  public HeimdallScope when() {
    return heimdall().scope(OptionalInt.empty());
  }

  @Override
  public HeimdallScope when(final int controlPlaneIndex) {
    return heimdall().scope(OptionalInt.of(controlPlaneIndex));
  }

  @Override
  public Optional<String> storeLeaderId() {
    if (storeClientEndpoints.isEmpty()) {
      return Optional.empty();
    }
    try (StoreClient client = new StoreClient(storeClientEndpoints)) {
      final String leaderId = client.status().leaderId();
      return leaderId.isEmpty() ? Optional.empty() : Optional.of(leaderId);
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<String> storeMemberIds() {
    if (storeClientEndpoints.isEmpty()) {
      return List.of();
    }
    try (StoreClient client = new StoreClient(storeClientEndpoints)) {
      return client.status().memberIds();
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  @Override
  public int storeCount() {
    return storeClientEndpoints.size();
  }

  @Override
  public Optional<GimleProcess> store(final int index) {
    return Optional.empty();
  }

  @Override
  public Optional<GimleProcess> storeLeader() {
    return Optional.empty();
  }

  @Override
  public Optional<GimleProcess> controlPlane(final int index) {
    return Optional.empty();
  }

  @Override
  public int fafnirCount() {
    // No process-control config for Fafnir here -- Fenrir's own fafnir-bounce candidate loop
    // already degrades cleanly to "no live Fafnir replica to bounce" whatever this reports, so
    // there is nothing genuine to count.
    return 0;
  }

  @Override
  public Optional<GimleProcess> fafnir(final int index) {
    return Optional.empty();
  }

  @Override
  public int muninnCount() {
    return muninnBaseUrls.size();
  }

  @Override
  public Optional<GimleProcess> muninn(final int index) {
    return Optional.empty();
  }

  @Override
  public boolean muninnServing(final int index) {
    return httpStatusOk(muninnBaseUrls.get(index) + "/status");
  }

  @Override
  public int andvariCount() {
    return andvariBaseUrls.size();
  }

  @Override
  public Optional<GimleProcess> andvari(final int index) {
    return Optional.empty();
  }

  @Override
  public boolean andvariServing(final int index) {
    return httpStatusOk(andvariBaseUrls.get(index) + "/status");
  }

  @Override
  public Optional<WorkerHandle> workerFor(final String deploymentName, final int instanceIndex) {
    return Optional.empty();
  }

  @Override
  public Optional<NetworkFaultInjector> faults() {
    return Optional.empty();
  }

  @Override
  public void close() {
    if (heimdall != null) {
      heimdall.close();
      heimdall = null;
    }
  }

  private synchronized Heimdall heimdall() {
    if (heimdall == null) {
      heimdall = Heimdall.attach(controlPlaneBaseUrls, List.of(), workDir, httpClient);
    }
    return heimdall;
  }

  private boolean httpStatusOk(final String url) {
    try {
      final HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (final IOException e) {
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
