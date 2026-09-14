package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ReleaseReconciler#previewWorkloads} is Ivaldi's own dry-run proxy's entire real-network
 * surface -- proved here against a stand-in control plane shaped like {@code ApiServer}'s real
 * {@code ?dryRun=true} route (a JSON verdict, always {@code 200} whether admitted or not; see that
 * route's own javadoc), rather than against {@link FakeControlPlane}, whose workload PUT ignores
 * the query string entirely and always actually applies.
 */
class ReleaseReconcilerPreviewTest {

  private FakeDryRunControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static RenderedBundle bundleOf(RenderedWorkload... workloads) {
    return new RenderedBundle(
        "suite", "1.0.0", List.of(), List.of(), List.of(), List.of(workloads));
  }

  @Test
  void previews_every_workload_in_declared_order_without_applying_anything() throws Exception {
    fake = new FakeDryRunControlPlane();
    RenderedBundle rendered =
        bundleOf(
            new RenderedWorkload("Deployment", "first-app", "kind: Deployment\nname: first-app\n"),
            new RenderedWorkload("Job", "second-job", "kind: Job\nname: second-job\n"));
    ControlPlaneApi api = new ControlPlaneApi(fake.address());

    List<Map<String, Object>> verdicts = ReleaseReconciler.previewWorkloads(api, rendered);

    assertEquals(2, verdicts.size());
    assertEquals("Deployment", verdicts.get(0).get("kind"));
    assertEquals("first-app", verdicts.get(0).get("name"));
    assertEquals(Boolean.TRUE, verdicts.get(0).get("admitted"));
    assertEquals("Job", verdicts.get(1).get("kind"));
    assertEquals("second-job", verdicts.get(1).get("name"));
    assertEquals(
        List.of("PUT /deployments/first-app?dryRun=true", "PUT /jobs/second-job?dryRun=true"),
        fake.requestsSeen);
    assertTrue(fake.applied.isEmpty(), "a preview must never actually apply a workload");
  }

  @Test
  void a_rejected_verdict_is_reported_rather_than_thrown() throws Exception {
    fake = new FakeDryRunControlPlane();
    fake.rejectNext("admission", "quota exceeded");
    RenderedBundle rendered =
        bundleOf(
            new RenderedWorkload(
                "Deployment", "over-quota", "kind: Deployment\nname: over-quota\n"));
    ControlPlaneApi api = new ControlPlaneApi(fake.address());

    List<Map<String, Object>> verdicts = ReleaseReconciler.previewWorkloads(api, rendered);

    // The control plane's own dry-run route always answers 200 -- admitted:false lives in the
    // body, never in the HTTP status -- so a rejected verdict round-trips as data, not a thrown
    // HilmirException the way a real (non-dry-run) PUT rejection would.
    assertEquals(1, verdicts.size());
    assertEquals(Boolean.FALSE, verdicts.get(0).get("admitted"));
    assertEquals(409L, verdicts.get(0).get("wouldRespondStatus"));
  }

  @Test
  void an_unreachable_control_plane_still_throws() {
    ControlPlaneApi api = new ControlPlaneApi("127.0.0.1:1");
    RenderedBundle rendered =
        bundleOf(new RenderedWorkload("Deployment", "app", "kind: Deployment\nname: app\n"));

    assertThrows(HilmirException.class, () -> ReleaseReconciler.previewWorkloads(api, rendered));
  }

  /** A minimal stand-in for {@code ApiServer}'s own {@code ?dryRun=true} workload PUT route. */
  private static final class FakeDryRunControlPlane implements AutoCloseable {
    private final HttpServer server;
    final List<String> requestsSeen = new CopyOnWriteArrayList<>();
    final List<String> applied = new CopyOnWriteArrayList<>();
    private volatile String rejectedStage;
    private volatile String rejectedDetail;

    FakeDryRunControlPlane() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::dispatch);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
    }

    String address() {
      return "127.0.0.1:" + server.getAddress().getPort();
    }

    /** The next (and only the next) workload previewed answers rejected, at the named stage. */
    void rejectNext(String stage, String detail) {
      rejectedStage = stage;
      rejectedDetail = detail;
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
      try {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        requestsSeen.add(method + " " + path + (query == null ? "" : "?" + query));
        if (!"PUT".equals(method) || !"dryRun=true".equals(query)) {
          // A preview must never reach the real apply path -- fail loudly rather than silently
          // recording an application this test exists to prove never happens.
          applied.add(method + " " + path);
          respond(exchange, 500, "this fake only answers ?dryRun=true PUTs");
          return;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        String kind = path.contains("/deployments/") ? "Deployment" : "Job";
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("dryRun", true);
        verdict.put("kind", kind);
        verdict.put("name", name);
        String stage = rejectedStage;
        if (stage != null) {
          rejectedStage = null;
          verdict.put("admitted", false);
          verdict.put("wouldRespondStatus", 409);
          verdict.put(
              "checks",
              List.of(Map.of("name", stage, "outcome", "FAILED", "detail", rejectedDetail)));
        } else {
          verdict.put("admitted", true);
          verdict.put("wouldRespondStatus", 200);
          verdict.put("checks", List.of());
        }
        respondJson(exchange, 200, verdict);
      } finally {
        exchange.close();
      }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }

    private static void respondJson(HttpExchange exchange, int status, Object value)
        throws IOException {
      respond(exchange, status, Json.write(value));
    }
  }
}
