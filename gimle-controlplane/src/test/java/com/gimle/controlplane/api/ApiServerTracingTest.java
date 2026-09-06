package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.observability.GimleTracing;
import com.gimle.testkit.Await;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The control plane's own spans. Its trace shipping was wired end to end -- exporter, shipper,
 * ingest path -- while nothing in the process ever started a span, so the history it shipped was
 * empty no matter how the cluster was configured, which from the outside is indistinguishable from
 * a broken shipper.
 *
 * <p>Holds {@code GlobalOpenTelemetry}'s process-wide state exclusively while it runs, and hands it
 * back on the way out: a tracer provider left installed would keep every later test in this fork
 * building and exporting real spans into a collector nothing reads.
 */
@ResourceLock(Resources.GLOBAL)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerTracingTest {

  private final CapturingExporter exporter = new CapturingExporter();

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;

  @BeforeEach
  void startServer() throws IOException {
    GimleTracing.install(exporter);
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
  }

  @AfterEach
  void stopServer() {
    GimleTracing.resetForTesting();
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  @Test
  @Timeout(30)
  void a_served_request_produces_a_span_naming_the_endpoint_it_hit() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/health"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());

    // flush() asks the batch processor to export now; the wait is for that export actually landing
    // in the exporter, which on a cold, contended JVM can outlast flush()'s own bounded join.
    GimleTracing.flush();
    Await.until(() -> hasHealthSpan(exporter.captured()), Duration.ofSeconds(15));

    assertTrue(
        hasHealthSpan(exporter.captured()),
        "the control plane must start a span of its own for a request it serves, got: "
            + exporter.captured());
  }

  private static boolean hasHealthSpan(List<SpanData> spans) {
    return spans.stream().anyMatch(span -> "GET /health".equals(span.getName()));
  }

  /** Local to this test: {@code gimle-observability}'s own equivalent is not published. */
  private static final class CapturingExporter implements SpanExporter {

    private final List<SpanData> captured = new CopyOnWriteArrayList<>();

    List<SpanData> captured() {
      return captured;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      captured.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
