package com.gimle.controlplane.alert;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSTs a small JSON body to {@code rule.webhookUrl()} -- the only production {@link
 * AlertNotifier}. Best-effort, the same posture {@code MuninnShipper} already takes toward its own
 * ingest endpoint: a webhook that's down or unreachable is logged and dropped, never allowed to
 * fail {@link AlertReconciler}'s own tick or retry indefinitely (a later tick's own transition, if
 * the condition still holds, is the natural retry).
 */
public final class WebhookAlertNotifier implements AlertNotifier {

  private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  public WebhookAlertNotifier() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
  }

  @Override
  public void notify(AlertNotification notification) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rule", notification.rule().name());
    notification.rule().tenantId().ifPresent(tenantId -> body.put("tenantId", tenantId));
    body.put("deploymentName", notification.rule().deploymentName());
    body.put("metric", notification.rule().metric().name());
    body.put("comparator", notification.rule().comparator().name());
    body.put("threshold", notification.rule().threshold());
    body.put("observedValue", notification.observedValue());
    body.put("state", notification.state().name());
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(notification.rule().webhookUrl()))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() >= 300) {
        log.warn(
            "alert webhook for rule '{}' returned status {}",
            notification.rule().name(),
            response.statusCode());
      }
    } catch (IOException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn(
          "alert webhook for rule '{}' failed: {}", notification.rule().name(), e.getMessage());
    }
  }
}
