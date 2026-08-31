package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.util.List;
import java.util.Map;

/**
 * Projects a workload's {@code get} status response (the nested {@code spec}/{@code instances}/
 * quota JSON {@code ApiServer.java}'s {@code *Status} methods return) back into a re-appliable
 * manifest -- the read-side counterpart to each workload command's own {@code apply}, which PUTs a
 * manifest matching this exact shape. Closes the round-trip gap where {@code status.spec.moduleId}
 * (nested, server-computed) could never be fed back as the manifest's own top-level {@code module:}
 * key: this rebuilds {@code kind:}/{@code name:}/{@code module:} at the manifest root, omits a
 * blank {@code artifactPath} entirely (a present-but-blank one is a manifest error -- {@code
 * ManifestFields.optionalArtifactPath}), and drops every status-only field ({@code instances},
 * {@code unplacedCount}, {@code quotaViolating}, ...) that no parser accepts. Hand- rolled YAML,
 * not a library, matching every other manifest writer in this codebase (see {@code gimle-hilmir}'s
 * {@code DeploymentYamlWriter}); double-quoted scalars via {@link Json#write}'s own string escaping
 * are valid YAML string syntax.
 *
 * <p>Known, accepted gap: fields the manifest parsers accept but no {@code *Status} method emits at
 * all (placement anti-affinity, configMapRefs/secretMapRefs, artifactSha256) are not present in the
 * status response this reads, so they cannot round-trip through this export either -- fixing that
 * is a server-side {@code *Status} change, not something a client-side projection can recover.
 */
final class ManifestExport {

  private ManifestExport() {}

  private static String q(Object value) {
    return Json.write(String.valueOf(value));
  }

  private static Map<?, ?> asMap(Object value) {
    return value instanceof Map<?, ?> m ? m : Map.of();
  }

  private static void moduleBlock(StringBuilder sb, String indent, Object rawModuleId) {
    Map<?, ?> moduleId = asMap(rawModuleId);
    sb.append(indent).append("module:\n");
    sb.append(indent).append("  name: ").append(q(moduleId.get("name"))).append('\n');
    sb.append(indent).append("  version: ").append(q(moduleId.get("version"))).append('\n');
  }

  /** Omitted entirely, not sent blank, matching every console toManifestYaml's own comment. */
  private static void artifactPathLine(StringBuilder sb, String indent, Object artifactPath) {
    if (artifactPath != null && !String.valueOf(artifactPath).isBlank()) {
      sb.append(indent).append("artifactPath: ").append(q(artifactPath)).append('\n');
    }
  }

  private static void tenantIdLine(StringBuilder sb, Object tenantId) {
    if (tenantId != null) {
      sb.append("tenantId: ").append(q(tenantId)).append('\n');
    }
  }

  private static void optionalNumberLine(
      StringBuilder sb, String indent, String key, Object value) {
    if (value != null) {
      sb.append(indent).append(key).append(": ").append(value).append('\n');
    }
  }

  static String deployment(Map<String, Object> status) {
    Map<?, ?> spec = asMap(status.get("spec"));
    StringBuilder sb = new StringBuilder();
    sb.append("kind: Deployment\n");
    sb.append("name: ").append(q(spec.get("name"))).append('\n');
    moduleBlock(sb, "", spec.get("moduleId"));
    artifactPathLine(sb, "", spec.get("artifactPath"));
    sb.append("replicas: ").append(spec.get("replicas")).append('\n');
    tenantIdLine(sb, spec.get("tenantId"));
    if (spec.get("autoscale") instanceof Map<?, ?> autoscale) {
      sb.append("autoscale:\n");
      sb.append("  minReplicas: ").append(autoscale.get("minReplicas")).append('\n');
      sb.append("  maxReplicas: ").append(autoscale.get("maxReplicas")).append('\n');
      sb.append("  targetCpuUtilizationPercent: ")
          .append(autoscale.get("targetCpuUtilizationPercent"))
          .append('\n');
      optionalNumberLine(
          sb, "  ", "targetRequestRatePerSecond", autoscale.get("targetRequestRatePerSecond"));
      optionalNumberLine(
          sb, "  ", "targetErrorRatePercent", autoscale.get("targetErrorRatePercent"));
      optionalNumberLine(sb, "  ", "targetQueueDepth", autoscale.get("targetQueueDepth"));
      // moduleId.combinationMode's WORST_SIGNAL/WEIGHTED enum name -> the manifest's own
      // worst-signal/weighted mode: key -- the one field name the wire status and the manifest
      // genuinely disagree on, matching every console toManifestYaml's identical mapping.
      boolean weighted = "WEIGHTED".equals(autoscale.get("combinationMode"));
      sb.append("  mode: ").append(weighted ? "weighted" : "worst-signal").append('\n');
      if (weighted) {
        optionalNumberLine(sb, "  ", "cpuWeight", autoscale.get("cpuWeight"));
        optionalNumberLine(sb, "  ", "requestRateWeight", autoscale.get("requestRateWeight"));
        optionalNumberLine(sb, "  ", "errorRateWeight", autoscale.get("errorRateWeight"));
        optionalNumberLine(sb, "  ", "queueDepthWeight", autoscale.get("queueDepthWeight"));
      }
    }
    if (spec.get("disruption") instanceof Map<?, ?> disruption) {
      sb.append("disruption:\n");
      sb.append("  maxUnavailable: ").append(disruption.get("maxUnavailable")).append('\n');
      sb.append("  maxSurge: ").append(disruption.get("maxSurge")).append('\n');
    }
    return sb.toString();
  }

  static String job(Map<String, Object> status) {
    Map<?, ?> spec = asMap(status.get("spec"));
    StringBuilder sb = new StringBuilder();
    sb.append("kind: Job\n");
    sb.append("name: ").append(q(spec.get("name"))).append('\n');
    moduleBlock(sb, "", spec.get("moduleId"));
    artifactPathLine(sb, "", spec.get("artifactPath"));
    sb.append("backoffLimit: ").append(spec.get("backoffLimit")).append('\n');
    optionalNumberLine(sb, "", "activeDeadlineSeconds", spec.get("activeDeadlineSeconds"));
    tenantIdLine(sb, spec.get("tenantId"));
    return sb.toString();
  }

  static String cronJob(Map<String, Object> status) {
    Map<?, ?> spec = asMap(status.get("spec"));
    Map<?, ?> jobTemplate = asMap(spec.get("jobTemplate"));
    StringBuilder sb = new StringBuilder();
    sb.append("kind: CronJob\n");
    sb.append("name: ").append(q(spec.get("name"))).append('\n');
    sb.append("schedule: ").append(q(spec.get("schedule"))).append('\n');
    sb.append("jobTemplate:\n");
    moduleBlock(sb, "  ", jobTemplate.get("moduleId"));
    artifactPathLine(sb, "  ", jobTemplate.get("artifactPath"));
    sb.append("  backoffLimit: ").append(jobTemplate.get("backoffLimit")).append('\n');
    optionalNumberLine(sb, "  ", "activeDeadlineSeconds", jobTemplate.get("activeDeadlineSeconds"));
    optionalNumberLine(sb, "", "startingDeadlineSeconds", spec.get("startingDeadlineSeconds"));
    sb.append("concurrencyPolicy: ").append(spec.get("concurrencyPolicy")).append('\n');
    tenantIdLine(sb, spec.get("tenantId"));
    return sb.toString();
  }

  static String daemonSet(Map<String, Object> status) {
    Map<?, ?> spec = asMap(status.get("spec"));
    StringBuilder sb = new StringBuilder();
    sb.append("kind: DaemonSet\n");
    sb.append("name: ").append(q(spec.get("name"))).append('\n');
    moduleBlock(sb, "", spec.get("moduleId"));
    artifactPathLine(sb, "", spec.get("artifactPath"));
    // No antiAffinity line, ever: DaemonSetManifestParser rejects the key outright if present --
    // one-per-node placement makes anti-affinity meaningless.
    Object rawLabels = asMap(spec.get("placement")).get("requiredLabels");
    if (rawLabels instanceof List<?> labels && !labels.isEmpty()) {
      sb.append("placement:\n").append("  requiredLabels: [");
      for (int i = 0; i < labels.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(q(labels.get(i)));
      }
      sb.append("]\n");
    }
    tenantIdLine(sb, spec.get("tenantId"));
    return sb.toString();
  }

  static String statefulSet(Map<String, Object> status) {
    Map<?, ?> spec = asMap(status.get("spec"));
    StringBuilder sb = new StringBuilder();
    sb.append("kind: StatefulSet\n");
    sb.append("name: ").append(q(spec.get("name"))).append('\n');
    moduleBlock(sb, "", spec.get("moduleId"));
    artifactPathLine(sb, "", spec.get("artifactPath"));
    sb.append("replicas: ").append(spec.get("replicas")).append('\n');
    tenantIdLine(sb, spec.get("tenantId"));
    return sb.toString();
  }
}
