package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.manifest.CronJobManifestParser;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DeploymentManifestParser;
import com.gimle.mimir.manifest.DeploymentSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code get <kind> <name> -o manifest} projects a status response back into a re-appliable
 * manifest, so each assertion here feeds the exported text straight back through the parser that
 * would receive it on an {@code apply} -- a field this export drops is a field an operator silently
 * loses by round-tripping their own workload through the CLI.
 */
class ManifestExportTest {

  private static Map<String, Object> moduleId() {
    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", "com.gimle.example.orders");
    moduleId.put("version", "1.2.0");
    return moduleId;
  }

  private static Map<String, Object> status(Map<String, Object> spec) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", spec);
    return status;
  }

  private static Map<String, Object> deploymentStatus(Map<String, Object> autoscale) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("name", "orders-service");
    spec.put("moduleId", moduleId());
    spec.put("artifactPath", "/var/gimle/artifacts/orders-1.2.0.jar");
    spec.put("replicas", 2);
    spec.put("autoscale", autoscale);
    return status(spec);
  }

  private static Map<String, Object> cronJobStatus(boolean suspend) {
    Map<String, Object> jobTemplate = new LinkedHashMap<>();
    jobTemplate.put("moduleId", moduleId());
    jobTemplate.put("artifactPath", "/var/gimle/artifacts/orders-1.2.0.jar");
    jobTemplate.put("backoffLimit", 6);
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("name", "nightly-cleanup");
    spec.put("schedule", "0 2 * * *");
    spec.put("jobTemplate", jobTemplate);
    spec.put("concurrencyPolicy", "ALLOW");
    spec.put("suspend", suspend);
    return status(spec);
  }

  private static DeploymentSpec reparseDeployment(String manifest) {
    return DeploymentManifestParser.parse(
        new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
  }

  private static CronJobSpec reparseCronJob(String manifest) {
    return CronJobManifestParser.parse(
        new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void an_exported_autoscale_block_round_trips_both_stabilization_windows() {
    Map<String, Object> autoscale = new LinkedHashMap<>();
    autoscale.put("minReplicas", 1);
    autoscale.put("maxReplicas", 5);
    autoscale.put("targetCpuUtilizationPercent", 50);
    autoscale.put("combinationMode", "WORST_SIGNAL");
    autoscale.put("scaleUpCooldownSeconds", 30L);
    autoscale.put("scaleDownCooldownSeconds", 900L);

    DeploymentSpec reparsed =
        reparseDeployment(ManifestExport.deployment(deploymentStatus(autoscale)));

    assertEquals(Duration.ofSeconds(30), reparsed.autoscale().orElseThrow().scaleUpCooldown());
    assertEquals(Duration.ofMinutes(15), reparsed.autoscale().orElseThrow().scaleDownCooldown());
  }

  @Test
  void an_exported_suspended_cronjob_is_still_suspended_when_applied_back() {
    CronJobSpec reparsed = reparseCronJob(ManifestExport.cronJob(cronJobStatus(true)));

    assertTrue(reparsed.suspend());
  }

  @Test
  void an_exported_running_cronjob_carries_no_suspend_line_at_all() {
    String manifest = ManifestExport.cronJob(cronJobStatus(false));

    assertFalse(manifest.contains("suspend"), "an unpaused CronJob's manifest stays free of noise");
    assertFalse(reparseCronJob(manifest).suspend());
  }
}
