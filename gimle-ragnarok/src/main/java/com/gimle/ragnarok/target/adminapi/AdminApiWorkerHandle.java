package com.gimle.ragnarok.target.adminapi;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.target.WorkerHandle;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A worker instance's pid, resolved from a node agent's own Admin Fault API -- the {@code
 * adminApi:} counterpart to {@code SshWorkerHandle}: deliberately simpler than {@code
 * SshManagedProcess}, since a worker-kill victim is resolved fresh for each strike (see {@code
 * AdminApiClusterTarget#workerFor}), so there is no restart to track and no pid file to re-read,
 * just the one pid captured at construction.
 *
 * <p>{@link #isAlive()} re-fetches the instance's own <em>current</em> status rather than checking
 * this exact historical pid in isolation -- the Admin Fault API only ever reports "the process
 * currently supervised for this instance," not an arbitrary pid's own liveness, unlike {@code kill
 * -0 &lt;pid&gt;} over SSH. A captured pid that no longer matches the current one (a respawn
 * already happened) is reported not-alive, which is the correct answer for this handle
 * specifically, even though a live process now genuinely does exist under a different pid.
 */
final class AdminApiWorkerHandle implements WorkerHandle {

  private final HttpClient httpClient;
  private final String adminBaseUrl;
  private final String deploymentName;
  private final int instanceIndex;
  private final long pid;

  AdminApiWorkerHandle(
      final HttpClient httpClient,
      final String adminBaseUrl,
      final String deploymentName,
      final int instanceIndex,
      final long pid) {
    this.httpClient = httpClient;
    this.adminBaseUrl = adminBaseUrl;
    this.deploymentName = deploymentName;
    this.instanceIndex = instanceIndex;
    this.pid = pid;
  }

  @Override
  public long pid() {
    return pid;
  }

  @Override
  public boolean isAlive() {
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(workerUrl())).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return false;
      }
      final Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      final long currentPid = ((Number) body.get("pid")).longValue();
      return currentPid == pid && Boolean.TRUE.equals(body.get("alive"));
    } catch (final IOException e) {
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void kill() {
    try {
      httpClient.send(
          HttpRequest.newBuilder(URI.create(workerUrl() + "/kill"))
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("pid", pid))))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (final IOException e) {
      // Best-effort, matching every other WorkerHandle.kill() in this codebase -- an unreachable
      // agent must not stop Fenrir's own recovery gate from running and reporting the real,
      // honest outcome.
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String workerUrl() {
    return adminBaseUrl + "/admin/faults/workers/" + deploymentName + "/" + instanceIndex;
  }
}
