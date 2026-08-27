package com.gimle.ragnarok.target.adminapi;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.ControlPlaneClient;
import com.gimle.ragnarok.target.ControlPlaneClient.InstancePlacement;
import com.gimle.ragnarok.target.GimleProcess;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.WorkerHandle;
import com.gimle.ragnarok.target.endpoint.EndpointClusterTarget;
import com.gimle.testkit.heimdall.HeimdallScope;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ClusterTarget} reaching a real, already-running cluster over the network only, exactly
 * like {@link EndpointClusterTarget} -- but additionally able to trigger {@code WORKER_KILL}
 * against a node's own Admin Fault API, without the SSH access {@code SshInventoryClusterTarget}
 * needs. Every network-facing method delegates straight to an internal {@link
 * EndpointClusterTarget}; {@link #workerFor} is the only thing this target adds, and {@link
 * #faults()} stays {@code Optional.empty()} -- no boot-time network interposition here either,
 * matching {@code SshInventoryClusterTarget}'s own honest gap for link-cut/store-partition.
 */
public final class AdminApiClusterTarget implements ClusterTarget {

  private final EndpointClusterTarget delegate;
  private final HttpClient httpClient;
  private final Map<String, String> adminEndpointByNodeId;

  public AdminApiClusterTarget(
      final List<String> controlPlaneBaseUrls,
      final HttpClient httpClient,
      final List<SocketAddress> storeClientEndpoints,
      final List<String> muninnBaseUrls,
      final List<String> andvariBaseUrls,
      final Path workDir,
      final AdminApiSpec adminApi) {
    this.delegate =
        new EndpointClusterTarget(
            controlPlaneBaseUrls,
            httpClient,
            storeClientEndpoints,
            muninnBaseUrls,
            andvariBaseUrls,
            workDir);
    this.httpClient = httpClient;
    this.adminEndpointByNodeId = adminApi.endpointByNodeId();
  }

  @Override
  public List<String> controlPlaneBaseUrls() {
    return delegate.controlPlaneBaseUrls();
  }

  @Override
  public int controlPlaneCount() {
    return delegate.controlPlaneCount();
  }

  @Override
  public ControlPlaneClient api() {
    return delegate.api();
  }

  @Override
  public ControlPlaneClient api(final int controlPlaneIndex) {
    return delegate.api(controlPlaneIndex);
  }

  @Override
  public HeimdallScope when() {
    return delegate.when();
  }

  @Override
  public HeimdallScope when(final int controlPlaneIndex) {
    return delegate.when(controlPlaneIndex);
  }

  @Override
  public Optional<String> storeLeaderId() {
    return delegate.storeLeaderId();
  }

  @Override
  public List<String> storeMemberIds() {
    return delegate.storeMemberIds();
  }

  @Override
  public int storeCount() {
    return delegate.storeCount();
  }

  @Override
  public Optional<GimleProcess> store(final int index) {
    return delegate.store(index);
  }

  @Override
  public Optional<GimleProcess> storeLeader() {
    return delegate.storeLeader();
  }

  @Override
  public Optional<GimleProcess> controlPlane(final int index) {
    return delegate.controlPlane(index);
  }

  @Override
  public int fafnirCount() {
    return delegate.fafnirCount();
  }

  @Override
  public Optional<GimleProcess> fafnir(final int index) {
    return delegate.fafnir(index);
  }

  @Override
  public int muninnCount() {
    return delegate.muninnCount();
  }

  @Override
  public Optional<GimleProcess> muninn(final int index) {
    return delegate.muninn(index);
  }

  @Override
  public boolean muninnServing(final int index) {
    return delegate.muninnServing(index);
  }

  @Override
  public int andvariCount() {
    return delegate.andvariCount();
  }

  @Override
  public Optional<GimleProcess> andvari(final int index) {
    return delegate.andvari(index);
  }

  @Override
  public boolean andvariServing(final int index) {
    return delegate.andvariServing(index);
  }

  @Override
  public Optional<WorkerHandle> workerFor(final String deploymentName, final int instanceIndex) {
    final Optional<String> nodeId =
        api().placements(deploymentName).stream()
            .filter(p -> p.instanceIndex() == instanceIndex)
            .map(InstancePlacement::nodeId)
            .findFirst();
    if (nodeId.isEmpty()) {
      return Optional.empty();
    }
    final String adminBaseUrl = adminEndpointByNodeId.get(nodeId.get());
    if (adminBaseUrl == null) {
      // Honest absence, same as every other accessor here: no admin endpoint declared for this
      // node.
      return Optional.empty();
    }
    final String url =
        adminBaseUrl + "/admin/faults/workers/" + deploymentName + "/" + instanceIndex;
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      final Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      if (!Boolean.TRUE.equals(body.get("alive"))) {
        return Optional.empty();
      }
      final long pid = ((Number) body.get("pid")).longValue();
      return Optional.of(
          new AdminApiWorkerHandle(httpClient, adminBaseUrl, deploymentName, instanceIndex, pid));
    } catch (final IOException e) {
      return Optional.empty();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  @Override
  public Optional<NetworkFaultInjector> faults() {
    // No boot-time interposition here either -- an adminApi target gains a second, SSH-free way to
    // kill a worker, not network-fault injection.
    return Optional.empty();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
