package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-cluster coverage for {@code kind: Job} and {@code kind: CronJob} -- previously proven only
 * by {@code JobReconcilerTest}/{@code CronJobReconcilerTest} against an in-memory store and by
 * {@code JobHooksExecutionTest} against an in-process {@code WorkerRuntime}, never through the full
 * real-process chain (agent spawns a real worker JVM, the worker drives {@code JobHooks#run} on its
 * own virtual thread, the resulting heartbeat's {@code lifecycleState: COMPLETED} is what {@code
 * JobReconciler} actually reads to declare the job {@code SUCCEEDED}). This is exactly the
 * unit-tested-but-never-run-against-a-real-cluster shape {@code QA_FINDINGS.md} repeatedly found
 * real bugs in elsewhere in this codebase.
 */
@Tag("smoke")
class JobLifecycleIT extends GreeterSmokeClusterSupport {

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_job_that_succeeds_reaches_the_succeeded_phase_and_stays_there() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    String moduleName = "com.gimle.fixture.quicksucceedingjob";
    Path jar = buildQuickSucceedingJobModuleJar(moduleName, "1.0.0");

    submitJobWithRetry(baseUrl, "quick-job", moduleName, "1.0.0", jar, 3, Duration.ofSeconds(30));

    Await.until(
        () -> jobPhaseIs(baseUrl, "quick-job", "SUCCEEDED"),
        Duration.ofSeconds(60),
        "quick-job should reach SUCCEEDED once a real worker JVM runs its JobHooks to completion");

    // A terminal phase must stay terminal, not flap back to RUNNING on a later reconcile tick --
    // poll across several real ticks (RECONCILE_INTERVAL = 2s) rather than trusting one read.
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      assertTrue(
          jobPhaseIs(baseUrl, "quick-job", "SUCCEEDED"),
          "a SUCCEEDED job must never revert to another phase on a later tick");
      Thread.sleep(1000);
    }
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_triggered_cronjob_generates_a_real_job_that_reaches_succeeded() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    String moduleName = "com.gimle.fixture.quicksucceedingcronjob";
    Path jar = buildQuickSucceedingJobModuleJar(moduleName, "1.0.0");

    // Once a year, so nothing here depends on -- or races -- the schedule's own natural firing;
    // the whole point of this test is the explicit gimle cronjob trigger path.
    submitCronJobWithRetry(
        baseUrl,
        "nightly-cleanup",
        moduleName,
        "1.0.0",
        jar,
        "0 0 1 1 *",
        "Allow",
        Duration.ofSeconds(30));

    String generatedJobName =
        triggerCronJobNowWithRetry(baseUrl, "nightly-cleanup", Duration.ofSeconds(10));
    assertTrue(
        generatedJobName.startsWith("nightly-cleanup-"),
        "a CronJob-generated Job name should be \"{cronJobName}-{epochSeconds}\", got: "
            + generatedJobName);

    Await.until(
        () -> jobPhaseIs(baseUrl, generatedJobName, "SUCCEEDED"),
        Duration.ofSeconds(60),
        "the CronJob-generated job " + generatedJobName + " should reach SUCCEEDED");

    // The generated Job is an ordinary entry in the real jobs list, not tracked separately --
    // confirms CronJobReconciler materialized a real, independently-visible Job resource, not
    // just an internal bookkeeping record only the CronJob's own status endpoint can see.
    List<Map<String, Object>> jobs = listJobs(baseUrl);
    boolean found =
        jobs.stream()
            .anyMatch(
                job -> {
                  Object spec = job.get("spec");
                  return spec instanceof Map<?, ?> specMap
                      && generatedJobName.equals(specMap.get("name"));
                });
    assertTrue(found, "GET /jobs should list the generated job " + generatedJobName);
  }
}
