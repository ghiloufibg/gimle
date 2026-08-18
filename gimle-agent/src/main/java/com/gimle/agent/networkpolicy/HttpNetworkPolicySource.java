package com.gimle.agent.networkpolicy;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real {@link NetworkPolicySource}: polls the control plane's {@code GET /networkpolicies} HTTP
 * API. Reuses the exact {@link HttpClient} request-building/response-parsing shape {@code
 * HttpServiceSource} already uses for Bifrost's own polling (same request timeout, same "non-200
 * becomes a {@link GimleClusterException}" rule) -- callers pass in the same {@link HttpClient}
 * instance {@code AgentMain} already built for its own control-plane calls.
 */
public final class HttpNetworkPolicySource implements NetworkPolicySource {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final URI controlPlaneBaseUrl;

  public HttpNetworkPolicySource(HttpClient httpClient, URI controlPlaneBaseUrl) {
    this.httpClient = httpClient;
    this.controlPlaneBaseUrl = controlPlaneBaseUrl;
  }

  @Override
  public List<NetworkPolicyRule> fetchTenantWidePolicies()
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(controlPlaneBaseUrl.resolve("/networkpolicies"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "list network policies", response.statusCode(), response.body());
    }
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<NetworkPolicyRule> rules = new ArrayList<>(raw.size());
    for (Object entryValue : raw) {
      Map<String, Object> entry = Json.asObject(entryValue);
      // Tenant-wide only: a policy scoped to specific deployments (a non-empty deploymentNames
      // array) is filtered out right here rather than delivered and ignored downstream --
      // enforcing it would need this worker to know which deployment(s) it hosts, plumbing this
      // first delivery slice deliberately doesn't add. See NetworkPolicyRule's own javadoc.
      if (entry.get("deploymentNames") instanceof List<?> deploymentNames
          && !deploymentNames.isEmpty()) {
        continue;
      }
      Set<String> allowedCallerTenantIds = new LinkedHashSet<>();
      for (Object tenantId : Json.asArray(entry.get("allowedCallerTenantIds"))) {
        allowedCallerTenantIds.add((String) tenantId);
      }
      rules.add(
          new NetworkPolicyRule(
              (String) entry.get("name"), (String) entry.get("tenantId"), allowedCallerTenantIds));
    }
    return rules;
  }
}
