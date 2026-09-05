package com.gimle.gateway;

import com.gimle.core.ingress.IngressRule;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the routes declared as {@code Ingress} resources from the control plane, so a gateway
 * serves them as its whole route table.
 *
 * <p>Polled rather than pushed, on the same level-triggered posture {@code gimle-bifrost}'s own
 * {@code HttpServiceSource} takes: each fetch returns the full current set and replaces what the
 * gateway was serving, so a missed poll self-heals on the next one and there is no incremental
 * state to drift. A failed fetch returns empty and the caller keeps its existing table -- an
 * unreachable control plane must not silently tear down routes that are working.
 */
public final class HttpIngressSource {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient;
  private final URI controlPlaneBaseUrl;

  public HttpIngressSource(HttpClient httpClient, URI controlPlaneBaseUrl) {
    this.httpClient = httpClient;
    this.controlPlaneBaseUrl = controlPlaneBaseUrl;
  }

  /**
   * Every route currently declared for {@code tenantId}, or empty when the control plane could not
   * be reached or answered anything but 200 -- deliberately indistinguishable to the caller, whose
   * correct response is the same either way: keep serving what it already has.
   */
  public Optional<List<IngressRule>> fetch(String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(controlPlaneBaseUrl.resolve("/ingresses"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Optional.empty();
    }
    List<IngressRule> rules = new ArrayList<>();
    for (Map<String, Object> ingress : Json.asObjectList(Json.parse(response.body()))) {
      if (!tenantId.equals(ingress.get("tenantId"))) {
        continue;
      }
      for (Map<String, Object> route : Json.asObjectList(ingress.get("routes"))) {
        rules.add(ruleFrom(route));
      }
    }
    return Optional.of(List.copyOf(rules));
  }

  private static IngressRule ruleFrom(Map<String, Object> route) {
    return new IngressRule(
        Optional.ofNullable((String) route.get("host")),
        (String) route.get("path"),
        Boolean.TRUE.equals(route.get("prefix")),
        IngressRule.Kind.valueOf(String.valueOf(route.get("kind")).toUpperCase(Locale.ROOT)),
        Optional.ofNullable((String) route.get("serviceName")),
        Optional.ofNullable((String) route.get("deploymentName")),
        Optional.ofNullable((String) route.get("portName")),
        Optional.ofNullable((String) route.get("interfaceName")),
        route.get("majorVersion") instanceof Number n ? n.intValue() : 0,
        Optional.ofNullable((String) route.get("methodName")),
        Optional.ofNullable((String) route.get("paramType")));
  }
}
