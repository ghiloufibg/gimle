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
import java.util.Optional;
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
  public List<NetworkPolicyRule> fetchPolicies() throws IOException, InterruptedException {
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
      rules.add(
          new NetworkPolicyRule(
              (String) entry.get("name"),
              (String) entry.get("tenantId"),
              // An empty scoping array is ApiServer#networkPolicyToJson's own convention for
              // "unscoped" -- mirrors NetworkPolicySpec's Optional.empty() the same way that
              // write side does. The direction sets are the opposite: their key is present
              // exactly when the policy restricts that direction, because there an empty set
              // (deny every cross-tenant peer) is distinct from absence.
              emptyMeansUnscoped(entry.get("deploymentNames")),
              emptyMeansUnscoped(entry.get("serviceInterfaceNames")),
              absentMeansUnrestricted(entry.get("allowedCallerTenantIds")),
              absentMeansUnrestricted(entry.get("allowedCalleeTenantIds"))));
    }
    return rules;
  }

  private static Optional<Set<String>> emptyMeansUnscoped(Object jsonArray) {
    Set<String> values = stringSet(jsonArray);
    return values.isEmpty() ? Optional.empty() : Optional.of(values);
  }

  private static Optional<Set<String>> absentMeansUnrestricted(Object jsonArray) {
    return jsonArray == null ? Optional.empty() : Optional.of(stringSet(jsonArray));
  }

  private static Set<String> stringSet(Object jsonArray) {
    if (jsonArray == null) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (Object value : Json.asArray(jsonArray)) {
      values.add((String) value);
    }
    return values;
  }
}
