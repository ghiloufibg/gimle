package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.ConcurrencyPolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.JobTemplate;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * What {@code GET /cronjobs} reports about a CronJob's schedule: {@code lastScheduleTime} means
 * "when a Job was last actually produced", which is not the same thing as the reconciler's own
 * cursor over the schedule -- that cursor is stamped the first time a CronJob is reconciled at all
 * and keeps advancing while the CronJob is suspended.
 */
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerCronJobStatusTest {

  private static final ModuleId MODULE = new ModuleId("com.acme.sweeper", Version.parse("1.0.0"));

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private void putCronJob(String name, boolean suspend) {
    JobTemplate template =
        new JobTemplate(
            MODULE,
            "/artifacts/sweeper.jar",
            PlacementConstraints.NONE,
            Optional.empty(),
            6,
            Optional.empty());
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutCronJobSpec(
                new CronJobSpec(
                    name,
                    "0 1 * * *",
                    template,
                    Optional.empty(),
                    ConcurrencyPolicy.ALLOW,
                    Optional.of(Tenant.DEFAULT_TENANT_ID),
                    3,
                    3,
                    suspend)));
  }

  /** A Job named the way {@code CronJobReconciler} names one it generated at {@code firedAt}. */
  private void putGeneratedJob(String cronJobName, Instant firedAt) {
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutJobSpec(
                new JobSpec(
                    cronJobName + "-" + firedAt.getEpochSecond(),
                    MODULE,
                    "/artifacts/sweeper.jar",
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    6,
                    Optional.of(Tenant.DEFAULT_TENANT_ID),
                    Optional.empty(),
                    Optional.empty())));
  }

  private Map<String, Object> cronJobStatus(String name) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
    List<Map<String, Object>> all = Json.asObjectList(Json.parse(response.body()));
    return all.stream()
        .filter(status -> name.equals(Json.asObject(status.get("spec")).get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cronjob named " + name + ": " + response.body()));
  }

  @Test
  @Timeout(20)
  void a_suspended_cronjob_that_never_fired_reports_no_last_schedule_time() throws Exception {
    putCronJob("nightly-sweep", true);
    // The cursor the reconciler keeps: recorded on the very first tick, then advanced past every
    // instant that came due while the CronJob stayed suspended. No Job was ever produced.
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutCronJobLastSchedule(
                Optional.of(Tenant.DEFAULT_TENANT_ID), "nightly-sweep", Instant.now()));

    Map<String, Object> status = cronJobStatus("nightly-sweep");

    assertNull(
        status.get("lastScheduleTime"),
        "a CronJob that has never produced a Job has no last schedule time: " + status);
    assertTrue(status.containsKey("scheduleEvaluatedThrough"), status.toString());
  }

  @Test
  @Timeout(20)
  void a_cronjob_reports_the_time_of_the_newest_job_it_actually_generated() throws Exception {
    putCronJob("nightly-sweep", false);
    Instant firstFiring = Instant.parse("2026-01-01T01:00:00Z");
    Instant latestFiring = Instant.parse("2026-01-03T01:00:00Z");
    putGeneratedJob("nightly-sweep", firstFiring);
    putGeneratedJob("nightly-sweep", latestFiring);
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutCronJobLastSchedule(
                Optional.of(Tenant.DEFAULT_TENANT_ID), "nightly-sweep", Instant.now()));

    Map<String, Object> status = cronJobStatus("nightly-sweep");

    assertEquals(latestFiring.toString(), status.get("lastScheduleTime"), status.toString());
  }

  /** A Job of another CronJob's, and one whose name merely starts the same, are not this one's. */
  @Test
  @Timeout(20)
  void another_cronjobs_generated_jobs_are_never_mistaken_for_this_ones() throws Exception {
    putCronJob("sweep", false);
    putGeneratedJob("sweep-extra", Instant.parse("2026-01-09T01:00:00Z"));

    Map<String, Object> status = cronJobStatus("sweep");

    assertNull(status.get("lastScheduleTime"), status.toString());
  }

  @Test
  @Timeout(20)
  void a_cronjob_never_reconciled_at_all_reports_neither_field() throws Exception {
    putCronJob("fresh-sweep", false);

    Map<String, Object> status = cronJobStatus("fresh-sweep");

    assertNull(status.get("lastScheduleTime"), status.toString());
    assertFalse(status.containsKey("scheduleEvaluatedThrough"), status.toString());
  }
}
