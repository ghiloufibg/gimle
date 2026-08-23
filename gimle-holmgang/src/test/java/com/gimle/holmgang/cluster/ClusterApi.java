package com.gimle.holmgang.cluster;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.QuotaSpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Scenario-facing client for one control-plane replica's HTTP API: submissions fail loudly on any
 * non-200, while the single-shot {@code is*} readers swallow transport failures ("not ready yet"
 * and "connection refused" both just mean "not yet", never a hard failure worth surfacing). Waiting
 * for state belongs to the Heimdall condition API, not here.
 */
public final class ClusterApi {

  private final HttpClient httpClient;
  private final String baseUrl;

  ClusterApi(final HttpClient httpClient, final String baseUrl) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public void putTenant(final String tenantId, final QuotaSpec quota) {
    expectOk("PUT", "/tenants/" + tenantId, tenantBody(quota), "tenant creation");
  }

  /**
   * Like {@link #putTenant}, but returns the raw status instead of failing on a non-200 -- for
   * scenarios asserting the rejection itself (an anonymous client's 401 under mTLS).
   */
  public int tryPutTenant(final String tenantId, final QuotaSpec quota) {
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                  .method(
                      "PUT",
                      HttpRequest.BodyPublishers.ofString(
                          tenantBody(quota), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException("tenant write attempt failed against " + baseUrl, e);
    }
  }

  private static String tenantBody(final QuotaSpec quota) {
    return Json.write(
        Map.of(
            "quota",
            Map.of(
                "maxMemoryBytes", quota.maxMemoryBytes(),
                "maxCpuMillicores", quota.maxCpuMillicores(),
                "maxInstances", quota.maxInstances())));
  }

  public void putAccount(final String username, final String password) {
    expectOk(
        "PUT",
        "/accounts/" + username,
        Json.write(Map.of("password", password)),
        "account creation");
  }

  public void putRole(final String name, final String resource, final String verb) {
    final String body =
        Json.write(Map.of("permissions", List.of(Map.of("resource", resource, "verb", verb))));
    expectOk("PUT", "/roles/" + name, body, "role creation");
  }

  /**
   * One scoped permission for the {@link #putRole(String, List)} overload: an unscoped grant has
   * {@code tenantScope} empty.
   */
  public record PermissionSpec(String resource, String verb, Optional<String> tenantScope) {}

  /**
   * Like {@link #putRole(String, String, String)}, but for a role granting more than one
   * permission, and/or a tenant-scoped one -- {@code tenantScope} present narrows the grant to that
   * tenant, matching {@code Permission}'s own optional scope.
   */
  public void putRole(final String name, final List<PermissionSpec> permissions) {
    final List<Map<String, Object>> permissionMaps = new ArrayList<>();
    for (final PermissionSpec permission : permissions) {
      final Map<String, Object> map = new LinkedHashMap<>();
      map.put("resource", permission.resource());
      map.put("verb", permission.verb());
      permission.tenantScope().ifPresent(scope -> map.put("tenantScope", scope));
      permissionMaps.add(map);
    }
    expectOk(
        "PUT",
        "/roles/" + name,
        Json.write(Map.of("permissions", permissionMaps)),
        "role creation");
  }

  /**
   * {@code true} once {@code GET /roles/{name}} answers 200 -- the read side of {@link
   * #putRole(String, String, String)}.
   */
  public boolean roleExists(final String name) {
    final Optional<HttpResponse<String>> response = tryGet("/roles/" + name);
    return response.isPresent() && response.get().statusCode() == 200;
  }

  /**
   * {@code subject} is {@code "user:<name>"} or {@code "group:<name>"}, matching {@code
   * RoleBinding}.
   */
  public void putRoleBinding(final String id, final String subject, final String roleName) {
    final String body = Json.write(Map.of("subject", subject, "roleName", roleName));
    expectOk("PUT", "/rolebindings/" + id, body, "role binding creation");
  }

  /** The read side of {@link #putRoleBinding}, {@code true} once {@code GET} answers 200. */
  public boolean roleBindingExists(final String id) {
    final Optional<HttpResponse<String>> response = tryGet("/rolebindings/" + id);
    return response.isPresent() && response.get().statusCode() == 200;
  }

  /**
   * True once {@code GET /accounts/{username}} answers 200 -- the account write is committed
   * through this same replica's own read path, not just accepted by the store leader. A caller
   * about to attempt a login should poll this first: {@code putAccount}'s 200 only means the write
   * was accepted, not that a read immediately afterward against the state machine is guaranteed to
   * observe it yet.
   */
  public boolean accountExists(final String username) {
    final Optional<HttpResponse<String>> response = tryGet("/accounts/" + username);
    return response.isPresent() && response.get().statusCode() == 200;
  }

  /** The outcome of one {@code POST /auth/login}: status, and a session cookie if it succeeded. */
  public record LoginResult(
      int status, Optional<String> sessionCookie, Optional<Long> retryAfterSeconds) {}

  public LoginResult login(final String username, final String password) {
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/login"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          Json.write(Map.of("username", username, "password", password)),
                          StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final Optional<String> cookie =
          response.headers().firstValue("Set-Cookie").map(ClusterApi::sessionCookieValue);
      final OptionalLong retryAfterHeader = response.headers().firstValueAsLong("Retry-After");
      final Optional<Long> retryAfter =
          retryAfterHeader.isPresent()
              ? Optional.of(retryAfterHeader.getAsLong())
              : Optional.empty();
      return new LoginResult(response.statusCode(), cookie, retryAfter);
    } catch (final Exception e) {
      throw new HolmgangException("login attempt failed against " + baseUrl, e);
    }
  }

  /** The {@code username} {@code GET /auth/session} resolves for {@code sessionCookie}, if any. */
  public Optional<String> sessionUsername(final String sessionCookie) {
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session"))
                  .header("Cookie", sessionCookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      final Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      if (Boolean.TRUE.equals(body.get("anonymous"))) {
        return Optional.empty();
      }
      return Optional.ofNullable((String) body.get("username"));
    } catch (final Exception e) {
      return Optional.empty();
    }
  }

  /**
   * A tenant write attempted under a session cookie rather than this client's own mTLS identity.
   */
  public int tryPutTenantAsSession(
      final String sessionCookie, final String tenantId, final QuotaSpec quota) {
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                  .header("Cookie", sessionCookie)
                  .method(
                      "PUT",
                      HttpRequest.BodyPublishers.ofString(
                          tenantBody(quota), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException(
          "session-scoped tenant write attempt failed against " + baseUrl, e);
    }
  }

  private static String sessionCookieValue(final String setCookieHeader) {
    final int semicolon = setCookieHeader.indexOf(';');
    return semicolon < 0 ? setCookieHeader : setCookieHeader.substring(0, semicolon);
  }

  /** Durable audit events matching {@code resourceKind}/{@code tenantId}, newest last. */
  public List<Map<String, Object>> auditEvents(final String resourceKind, final String tenantId) {
    final Optional<HttpResponse<String>> response =
        tryGet("/audit?resource=" + resourceKind + "&tenant=" + tenantId);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    try {
      return Json.asObjectList(Json.parse(response.get().body()));
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  public void putSecret(final String tenantId, final String key, final String value) {
    final String body =
        Json.write(
            Map.of(
                "value",
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    expectOk("PUT", "/secrets/" + tenantId + "/" + key, body, "secret write");
  }

  public void putConfig(final String tenantId, final String key, final String value) {
    final String body = Json.write(Map.of("value", value, "encrypted", false));
    expectOk("PUT", "/config/" + tenantId + "/" + key, body, "config write");
  }

  /**
   * {@code GET /secrets/{tenantId}/{key}[?version=N]}, decoded from base64 -- empty for a 404
   * (never written, or soft-deleted at the latest version with no {@code version} given), matching
   * this class's own "single-shot readers swallow transport/not-found, never a hard failure" style.
   */
  public Optional<String> getSecret(
      final String tenantId, final String key, final Optional<Integer> version) {
    final String path =
        "/secrets/" + tenantId + "/" + key + version.map(v -> "?version=" + v).orElse("");
    final Optional<HttpResponse<String>> response = tryGet(path);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return Optional.empty();
    }
    final Map<String, Object> body = Json.asObject(Json.parse(response.get().body()));
    final byte[] decoded = Base64.getDecoder().decode((String) body.get("value"));
    return Optional.of(new String(decoded, StandardCharsets.UTF_8));
  }

  /** {@code GET /secrets/{tenantId}/{key}/versions} -- the version numbers currently stored. */
  public List<Integer> secretVersions(final String tenantId, final String key) {
    final Optional<HttpResponse<String>> response =
        tryGet("/secrets/" + tenantId + "/" + key + "/versions");
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    final Map<String, Object> body = Json.asObject(Json.parse(response.get().body()));
    return Json.asArray(body.get("versions")).stream().map(v -> ((Number) v).intValue()).toList();
  }

  /** Soft delete: {@code DELETE /secrets/{tenantId}/{key}} -- historical versions stay readable. */
  public void softDeleteSecret(final String tenantId, final String key) {
    expectOkNoBody("DELETE", "/secrets/" + tenantId + "/" + key, "secret soft delete");
  }

  /**
   * Hard delete: {@code DELETE /secrets/{tenantId}/{key}?destroy=true} -- every version is gone.
   */
  public void hardDeleteSecret(final String tenantId, final String key) {
    expectOkNoBody(
        "DELETE", "/secrets/" + tenantId + "/" + key + "?destroy=true", "secret hard delete");
  }

  /**
   * {@code POST /secrets/rotate-key}, proxied to Fafnir -- generates a new active secrets master
   * key and re-encrypts every existing entry under it. Returns the newly active key id.
   */
  public int rotateSecretsKey() {
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/rotate-key"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("secrets key rotation failed against " + baseUrl, e);
    }
    requireOk(response, "/secrets/rotate-key", "secrets key rotation");
    return ((Number) Json.asObject(Json.parse(response.body())).get("activeKeyId")).intValue();
  }

  /** A deployment's {@code disruption:} budget; absent implies the platform's own default. */
  public record Disruption(int maxUnavailable, int maxSurge) {}

  public void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    submitDeployment(
        deploymentName, moduleName, moduleVersion, jar, replicas, tenantId, Optional.empty());
  }

  public void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId,
      final Optional<Disruption> disruption) {
    expectOk(
        "PUT",
        "/deployments/" + deploymentName,
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.of(jar),
            replicas,
            tenantId,
            disruption),
        "deployment submission");
  }

  /**
   * Submits a deployment naming no {@code artifactPath} at all: the module coordinate alone, which
   * the control plane and the placing node agent each resolve through the artifact registry.
   */
  public void submitDeploymentByCoordinate(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final int replicas,
      final Optional<String> tenantId) {
    expectOk(
        "PUT",
        "/deployments/" + deploymentName,
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.empty(),
            replicas,
            tenantId,
            Optional.empty()),
        "coordinate-only deployment submission");
  }

  /**
   * Pushes a module jar to the artifact registry through the control plane's own {@code
   * /artifacts/*} proxy -- the same route {@code gimle artifact push} takes, rather than straight
   * at the Andvari port. The body is streamed from the file, never buffered whole.
   */
  public void pushArtifact(final String moduleName, final String moduleVersion, final Path jar) {
    final String path = "/artifacts/" + moduleName + "/" + moduleVersion;
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .PUT(HttpRequest.BodyPublishers.ofFile(jar.toAbsolutePath()))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("artifact push failed against " + baseUrl + path, e);
    }
    requireOk(response, path, "artifact push");
  }

  /**
   * Like {@link #submitDeployment}, but returns the raw status instead of failing on a non-200 --
   * for scenarios asserting the rejection itself (admission control's 409).
   */
  public int trySubmitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    final String manifest =
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.of(jar),
            replicas,
            tenantId,
            Optional.empty());
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                  .method(
                      "PUT", HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException("deployment submission attempt failed against " + baseUrl, e);
    }
  }

  /**
   * Submits a deployment with an {@code autoscale:} policy. {@code targetCpuUtilizationPercent} is
   * always written (the platform requires it); the request-rate target is optional, matching the
   * policy's own "absent means not evaluated" shape.
   */
  public void submitAutoscaleDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int minReplicas,
      final int maxReplicas,
      final int targetCpuUtilizationPercent,
      final Optional<Double> targetRequestRatePerSecond) {
    final String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        autoscale:
          minReplicas: %d
          maxReplicas: %d
          targetCpuUtilizationPercent: %d
        %s"""
            .formatted(
                deploymentName,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                minReplicas,
                minReplicas,
                maxReplicas,
                targetCpuUtilizationPercent,
                targetRequestRatePerSecond
                    .map(rate -> "  targetRequestRatePerSecond: " + rate + "\n")
                    .orElse(""));
    expectOk("PUT", "/deployments/" + deploymentName, manifest, "autoscale deployment submission");
  }

  /** True once {@code GET /tenants/{id}} answers 200 -- the read side of a workload's writes. */
  public boolean tenantExists(final String tenantId) {
    final Optional<HttpResponse<String>> response = tryGet("/tenants/" + tenantId);
    return response.isPresent() && response.get().statusCode() == 200;
  }

  public void deleteDeployment(final String deploymentName) {
    expectOkNoBody("DELETE", "/deployments/" + deploymentName, "deployment deletion");
  }

  /**
   * Rolls back to the revision immediately before the deployment's current one -- no {@code
   * toRevision} in the request body, matching {@code gimle deployment rollback <name>}'s own
   * no-flag default.
   */
  public void rollbackDeployment(final String deploymentName) {
    expectOkNoBody("POST", "/deployments/" + deploymentName + "/rollback", "deployment rollback");
  }

  public void deleteStatefulSet(final String name) {
    expectOkNoBody("DELETE", "/statefulsets/" + name, "statefulset deletion");
  }

  public void deleteTenant(final String tenantId) {
    expectOkNoBody("DELETE", "/tenants/" + tenantId, "tenant deletion");
  }

  public void cordonNode(final String nodeId) {
    expectOkNoBody("POST", "/nodes/" + nodeId + "/cordon", "node cordon");
  }

  public void uncordonNode(final String nodeId) {
    expectOkNoBody("POST", "/nodes/" + nodeId + "/uncordon", "node uncordon");
  }

  /** An absent {@code jar} writes no {@code artifactPath} line: a registry-coordinate manifest. */
  private static String deploymentManifest(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Optional<Path> jar,
      final int replicas,
      final Optional<String> tenantId,
      final Optional<Disruption> disruption) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        %sreplicas: %d
        %s%s
        """
        .formatted(
            deploymentName,
            moduleName,
            moduleVersion,
            jar.map(path -> "artifactPath: " + path.toAbsolutePath() + "\n").orElse(""),
            replicas,
            tenantId.map(id -> "tenantId: " + id + "\n").orElse(""),
            disruption
                .map(
                    d ->
                        "disruption:\n  maxUnavailable: %d\n  maxSurge: %d\n"
                            .formatted(d.maxUnavailable(), d.maxSurge()))
                .orElse(""));
  }

  public boolean isDeploymentActive(final String deploymentName) {
    try {
      final List<Map<String, Object>> instances = instancesOf(deploymentName);
      if (instances.isEmpty()) {
        return false;
      }
      for (final Map<String, Object> instance : instances) {
        if (!(instance.get("observation") instanceof Map<?, ?> observation)
            || !"ACTIVE".equals(observation.get("lifecycleState"))) {
          return false;
        }
      }
      return true;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  public int activeInstanceCount(final String deploymentName) {
    try {
      int active = 0;
      for (final Map<String, Object> instance : instancesOf(deploymentName)) {
        if (instance.get("observation") instanceof Map<?, ?> observation
            && "ACTIVE".equals(observation.get("lifecycleState"))) {
          active++;
        }
      }
      return active;
    } catch (final RuntimeException e) {
      return 0;
    }
  }

  public boolean nodeRegistered(final String nodeId) {
    final Optional<HttpResponse<String>> response = tryGet("/nodes");
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return false;
    }
    try {
      for (final Object entry : Json.asArray(Json.parse(response.get().body()))) {
        if (nodeId.equals(Json.asObject(entry).get("nodeId"))) {
          return true;
        }
      }
      return false;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  public boolean instanceLogContains(
      final String deploymentName,
      final int instanceIndex,
      final String category,
      final String text) {
    final Optional<HttpResponse<String>> response =
        tryGet("/logs/instances/" + deploymentName + "/" + instanceIndex + "?category=" + category);
    return response.isPresent()
        && response.get().statusCode() == 200
        && response.get().body().contains(text);
  }

  /**
   * True once {@code GET /metrics-history/{processKind}/{processId}} answers 200 and its body
   * contains {@code text} -- the control plane's read-only proxy to Muninn's own shipped-metrics
   * history, which (unlike {@code /logs/*}) has no live-process fallback, so a hit here can only
   * come from Muninn itself.
   */
  public boolean metricsHistoryContains(
      final String processKind, final String processId, final String text) {
    final Optional<HttpResponse<String>> response =
        tryGet("/metrics-history/" + processKind + "/" + processId);
    return response.isPresent()
        && response.get().statusCode() == 200
        && response.get().body().contains(text);
  }

  public boolean isServing() {
    final Optional<HttpResponse<String>> response = tryGet("/deployments");
    return response.isPresent() && response.get().statusCode() < 500;
  }

  /** One instance's placement: its index, the node hosting it, and its lifecycle state. */
  public record InstancePlacement(int instanceIndex, String nodeId, String lifecycleState) {}

  /** Every instance of a deployment with its node and state -- the placement-spread source. */
  public List<InstancePlacement> placements(final String deploymentName) {
    final List<InstancePlacement> placements = new ArrayList<>();
    for (final Map<String, Object> instance : instancesOf(deploymentName)) {
      final String state =
          instance.get("observation") instanceof Map<?, ?> observation
              ? String.valueOf(observation.get("lifecycleState"))
              : "UNOBSERVED";
      placements.add(
          new InstancePlacement(
              ((Number) instance.get("instanceIndex")).intValue(),
              String.valueOf(instance.get("nodeId")),
              state));
    }
    return placements;
  }

  /**
   * One instance's platform lifecycle event log ({@code GET /events}): each entry carries the
   * platform's own {@code occurredAtEpochMilli}, {@code kind}, and {@code message}. Empty when the
   * instance has no events yet or the read fails. This is the authoritative source for
   * startup-phase latencies -- timestamps the platform recorded, not the harness observed.
   */
  public List<Map<String, Object>> instanceEvents(
      final String deploymentName, final int instanceIndex) {
    final Optional<HttpResponse<String>> response =
        tryGet("/events?deployment=" + deploymentName + "&instance=" + instanceIndex);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    try {
      return Json.asObjectList(Json.parse(response.get().body()));
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  private List<Map<String, Object>> instancesOf(final String deploymentName) {
    return instancesUnder("/deployments/" + deploymentName);
  }

  /** Shared by every workload kind's status route -- each returns the identical instances shape. */
  private List<Map<String, Object>> instancesUnder(final String path) {
    final Optional<HttpResponse<String>> response = tryGet(path);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    return Json.asObjectList(Json.asObject(Json.parse(response.get().body())).get("instances"));
  }

  /** Like {@link #submitDeployment}, but for a {@code kind: StatefulSet} manifest. */
  public void submitStatefulSet(
      final String name,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    expectOk(
        "PUT",
        "/statefulsets/" + name,
        """
        kind: StatefulSet
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        %s"""
            .formatted(
                name,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                replicas,
                tenantId.map(id -> "tenantId: " + id + "\n").orElse("")),
        "statefulset submission");
  }

  /**
   * {@code GET /cronjobs/{name}}'s own {@code lastScheduleTime}, present only once the real
   * reconciler has actually fired this CronJob at least once. Empty on any read failure too.
   */
  public Optional<String> cronJobLastScheduleTime(final String name) {
    final Optional<HttpResponse<String>> response = tryGet("/cronjobs/" + name);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return Optional.empty();
    }
    final Object value = Json.asObject(Json.parse(response.get().body())).get("lastScheduleTime");
    return value instanceof String s ? Optional.of(s) : Optional.empty();
  }

  /**
   * {@link #placements(String)}'s counterpart for a StatefulSet's own {@code /statefulsets} route.
   */
  public List<InstancePlacement> statefulSetPlacements(final String name) {
    final List<InstancePlacement> placements = new ArrayList<>();
    for (final Map<String, Object> instance : instancesUnder("/statefulsets/" + name)) {
      final String state =
          instance.get("observation") instanceof Map<?, ?> observation
              ? String.valueOf(observation.get("lifecycleState"))
              : "UNOBSERVED";
      placements.add(
          new InstancePlacement(
              ((Number) instance.get("instanceIndex")).intValue(),
              String.valueOf(instance.get("nodeId")),
              state));
    }
    return placements;
  }

  /**
   * A raw {@code PUT} of {@code yaml} against {@code path} (e.g. {@code /daemonsets/foo}), status
   * code only -- the generic entry point for manifest-kind parsing/validation scenarios that need
   * to submit a hand-built manifest (including a deliberately invalid one) and assert on acceptance
   * or rejection, rather than on the deployment ever reaching a live state.
   */
  public int tryPutManifest(final String path, final String yaml) {
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method("PUT", HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException(
          "manifest submission attempt failed against " + baseUrl + path, e);
    }
  }

  /**
   * {@code GET /audit}, filtered exactly as the real API filters it -- each entry's own {@code
   * resourceKind}/{@code tenantId} as parsed JSON maps, newest-first as the server already returns
   * them. Empty (never a hard failure) when the read itself fails, matching every other best-effort
   * reader in this class.
   */
  public List<Map<String, Object>> auditEvents(
      final Optional<String> resourceKind, final Optional<String> tenantId) {
    final StringBuilder path = new StringBuilder("/audit?");
    resourceKind.ifPresent(kind -> path.append("resource=").append(kind).append('&'));
    tenantId.ifPresent(tenant -> path.append("tenant=").append(tenant).append('&'));
    final Optional<HttpResponse<String>> response = tryGet(path.toString());
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    return Json.asObjectList(Json.parse(response.get().body()));
  }

  /**
   * {@code lastHeartbeatAt} off {@code GET /nodes} for one node -- present only once that node has
   * ever reported a heartbeat the leader observed. Empty on any read failure or absent node.
   */
  public Optional<String> nodeLastHeartbeatAt(final String nodeId) {
    final Optional<HttpResponse<String>> response = tryGet("/nodes");
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return Optional.empty();
    }
    for (final Object entry : Json.asArray(Json.parse(response.get().body()))) {
      final Map<String, Object> node = Json.asObject(entry);
      if (nodeId.equals(node.get("nodeId")) && node.get("lastHeartbeatAt") instanceof String at) {
        return Optional.of(at);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code POST /nodes/{nodeId}/events}, the exact relay route a real node agent posts its own
   * worker-reported {@link com.gimle.core.protocol.InstanceEvent}s through -- reused directly here
   * rather than a synthetic shortcut, since the route's own contract (an event id, deployment name,
   * instance index, kind, message, occurrence time) is already a plain JSON body any caller can
   * post.
   */
  public void postInstanceEvent(
      final String nodeId,
      final String eventId,
      final String deploymentName,
      final int instanceIndex,
      final String kind,
      final String message,
      final long occurredAtEpochMilli) {
    final String body =
        Json.write(
            Map.of(
                "id", eventId,
                "deploymentName", deploymentName,
                "instanceIndex", instanceIndex,
                "kind", kind,
                "message", message,
                "occurredAtEpochMilli", occurredAtEpochMilli));
    expectOk("POST", "/nodes/" + nodeId + "/events", body, "instance event relay");
  }

  private Optional<HttpResponse<String>> tryGet(final String path) {
    try {
      return Optional.of(
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    } catch (final Exception e) {
      return Optional.empty();
    }
  }

  private void expectOkNoBody(final String method, final String path, final String description) {
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method(method, HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException(description + " failed against " + baseUrl + path, e);
    }
    requireOk(response, path, description);
  }

  private void expectOk(
      final String method, final String path, final String body, final String description) {
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException(description + " failed against " + baseUrl + path, e);
    }
    requireOk(response, path, description);
  }

  private void requireOk(
      final HttpResponse<String> response, final String path, final String description) {
    if (response.statusCode() != 200) {
      throw new HolmgangException(
          description
              + " failed: "
              + response.statusCode()
              + " "
              + response.body()
              + " ("
              + baseUrl
              + path
              + ")");
    }
  }
}
