package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.surtr.Measurements.ApiCall;
import com.gimle.ragnarok.surtr.Measurements.FailureCounts;
import com.gimle.ragnarok.surtr.Measurements.MetricPoint;
import com.gimle.ragnarok.surtr.Measurements.MetricSeries;
import com.gimle.ragnarok.surtr.Measurements.PlacementSpread;
import com.gimle.ragnarok.surtr.Measurements.StartupLatency;
import com.gimle.ragnarok.surtr.SurtrJob.ChurnSpec;
import com.gimle.ragnarok.surtr.SurtrRunResult.GateOutcome;
import com.gimle.ragnarok.surtr.SurtrRunResult.JobSummary;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.ControlPlaneClient;
import com.gimle.testkit.heimdall.HeimdallConditionError;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Runs a {@link SurtrWorkload} against a live cluster: each job in order, submissions paced by a
 * token bucket, then measurements collected -- API latency from the client clock, startup latency
 * from the platform's own event log, failures and placement from cluster state -- and the gates
 * evaluated into a {@link SurtrRunResult}. Objects created are garbage-collected at the end when
 * the workload asks, after measurements are taken.
 */
public final class SurtrRunner {

  private static final String BASE_VERSION = "1.0.0";
  private static final long DEFAULT_QUOTA_MAX_MEMORY_BYTES = 268_435_456;
  private static final long DEFAULT_QUOTA_MAX_CPU_MILLICORES = 1000;
  private static final int DEFAULT_QUOTA_MAX_INSTANCES = 100;

  private final ClusterTarget cluster;
  private final SurtrWorkload workload;
  private final ModuleJarSource moduleJarSource;
  private final double scale;
  private final ControlPlaneClient api;
  private final Random churnRng = new Random(Long.getLong("gimle.holmgang.chaosSeed", 0xF00DL));

  private final List<ApiCall> apiCalls = new ArrayList<>();
  private final List<String> createdDeployments = new ArrayList<>();
  private final List<String> createdTenants = new ArrayList<>();
  private final Map<String, Integer> deploymentReplicas = new LinkedHashMap<>();
  private final Map<String, Long> deploymentSubmitEpoch = new LinkedHashMap<>();
  private final Map<String, String> deploymentModule = new LinkedHashMap<>();
  private final Map<String, Optional<String>> deploymentTenant = new LinkedHashMap<>();
  private int failedSubmissions;

  public SurtrRunner(
      final ClusterTarget cluster,
      final SurtrWorkload workload,
      final ModuleJarSource moduleJarSource) {
    this.cluster = cluster;
    this.workload = workload;
    this.moduleJarSource = moduleJarSource;
    this.scale = SurtrWorkload.scaleFactor();
    this.api = cluster.api();
  }

  public SurtrRunResult run() {
    final long startedAt = System.currentTimeMillis();
    final List<JobSummary> summaries = new ArrayList<>();
    for (final SurtrJob job : workload.jobs()) {
      summaries.add(runJob(job));
    }
    final Optional<StartupLatency> startup =
        wants(Measurement.INSTANCE_STARTUP_LATENCY)
            ? Optional.of(collectStartupLatency())
            : Optional.empty();
    final Optional<PlacementSpread> placement =
        wants(Measurement.PLACEMENT_SPREAD)
            ? Optional.of(collectPlacementSpread())
            : Optional.empty();
    final FailureCounts failures = collectFailures();
    final List<MetricSeries> muninn =
        wants(Measurement.MUNINN_WINDOW) ? skippedMuninn() : List.of();
    final List<Measurements.ApiStat> apiLatency =
        wants(Measurement.API_LATENCY) ? Measurements.aggregate(apiCalls) : List.of();
    final List<GateOutcome> gates = evaluateGates(failures, startup);
    final long finishedAt = System.currentTimeMillis();
    final SurtrRunResult result =
        new SurtrRunResult(
            workload.name(),
            scale,
            workload.topology(),
            startedAt,
            finishedAt,
            summaries,
            startup,
            apiLatency,
            placement,
            failures,
            muninn,
            gates);
    if (workload.gc()) {
      gc();
    }
    return result;
  }

  private JobSummary runJob(final SurtrJob job) {
    final long start = System.currentTimeMillis();
    final int submitted;
    submitted =
        switch (job.type()) {
          case CREATE -> runCreate(job);
          case CHURN -> runChurn(job);
          case DELETE -> runDelete(job);
        };
    return new JobSummary(
        job.name(),
        job.type().name().toLowerCase(java.util.Locale.ROOT),
        effectiveIterations(job),
        System.currentTimeMillis() - start,
        submitted,
        failedSubmissions);
  }

  private int runCreate(final SurtrJob job) {
    final int iterations = effectiveIterations(job);
    final TokenBucket bucket = new TokenBucket(job.qps(), job.burst());
    int submitted = 0;
    final List<String> jobDeployments = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      for (final ObjectTemplate template : job.objects()) {
        bucket.acquire();
        final ObjectTemplate object = template.expand(job.name(), i);
        submitted++;
        if (object.kind() == ObjectTemplate.Kind.TENANT) {
          submitTenant(object);
        } else {
          if (submitDeployment(object, BASE_VERSION)) {
            jobDeployments.add(object.name());
          }
        }
      }
    }
    if (!jobDeployments.isEmpty()) {
      awaitAllActive(jobDeployments, job.waitForActive());
    }
    return submitted;
  }

  private int runChurn(final SurtrJob job) {
    final ChurnSpec churn = job.churn();
    final List<String> targets = new ArrayList<>(deploymentsOfJob(job.target().orElseThrow()));
    if (targets.isEmpty()) {
      return 0;
    }
    final long deadline = System.nanoTime() + churn.duration().toNanos();
    int churned = 0;
    int version = 1;
    while (System.nanoTime() < deadline) {
      final List<String> chosen = sample(targets, churn.percent());
      for (final String name : chosen) {
        if (churn.mode() == SurtrJob.ChurnMode.REDEPLOY) {
          resubmit(name, "1.0." + version);
        } else {
          recreate(name);
        }
        churned++;
      }
      version++;
      awaitAllActive(chosen, Duration.ofSeconds(120));
    }
    return churned;
  }

  private int runDelete(final SurtrJob job) {
    final List<String> targets = new ArrayList<>(deploymentsOfJob(job.target().orElseThrow()));
    for (final String name : targets) {
      deleteDeployment(name);
    }
    return targets.size();
  }

  private void submitTenant(final ObjectTemplate object) {
    final long begin = System.nanoTime();
    final int status =
        api.tryPutTenant(
            object.name(),
            DEFAULT_QUOTA_MAX_MEMORY_BYTES,
            DEFAULT_QUOTA_MAX_CPU_MILLICORES,
            DEFAULT_QUOTA_MAX_INSTANCES);
    record("/tenants", "PUT", begin, status);
    if (status == 200) {
      createdTenants.add(object.name());
    } else {
      failedSubmissions++;
    }
  }

  private boolean submitDeployment(final ObjectTemplate object, final String version) {
    final String moduleArtifact = object.module().orElseThrow();
    final long begin = System.nanoTime();
    final long submitEpoch = System.currentTimeMillis();
    final int status =
        api.trySubmitDeployment(
            object.name(),
            moduleJarSource.moduleName(moduleArtifact),
            version,
            moduleJarSource.jar(moduleArtifact),
            object.replicas(),
            object.tenant());
    record("/deployments", "PUT", begin, status);
    if (status != 200) {
      failedSubmissions++;
      return false;
    }
    createdDeployments.add(object.name());
    deploymentReplicas.put(object.name(), object.replicas());
    deploymentSubmitEpoch.put(object.name(), submitEpoch);
    deploymentModule.put(object.name(), moduleArtifact);
    deploymentTenant.put(object.name(), object.tenant());
    return true;
  }

  private void resubmit(final String name, final String version) {
    final long begin = System.nanoTime();
    final int status =
        api.trySubmitDeployment(
            name,
            moduleJarSource.moduleName(deploymentModule.get(name)),
            version,
            moduleJarSource.jar(deploymentModule.get(name)),
            deploymentReplicas.get(name),
            deploymentTenant.get(name));
    record("/deployments", "PUT", begin, status);
    if (status != 200) {
      failedSubmissions++;
    }
  }

  private void recreate(final String name) {
    deleteDeployment(name);
    cluster.when().deployment(name).isAbsent().await(Duration.ofSeconds(120));
    final long begin = System.nanoTime();
    final int status =
        api.trySubmitDeployment(
            name,
            moduleJarSource.moduleName(deploymentModule.get(name)),
            BASE_VERSION,
            moduleJarSource.jar(deploymentModule.get(name)),
            deploymentReplicas.get(name),
            deploymentTenant.get(name));
    record("/deployments", "PUT", begin, status);
    if (status != 200) {
      failedSubmissions++;
    }
  }

  private void deleteDeployment(final String name) {
    final long begin = System.nanoTime();
    try {
      api.deleteDeployment(name);
      record("/deployments", "DELETE", begin, 200);
    } catch (final RuntimeException e) {
      // Caught broadly, not as RagnarokException: which unchecked type a ControlPlaneClient
      // implementation throws on failure is its own business, not part of this interface's
      // contract.
      record("/deployments", "DELETE", begin, 500);
    }
  }

  /**
   * Waits, through a single Heimdall condition, for every named deployment to reach ACTIVE. A miss
   * is not a hard failure here -- it is caught so the {@code maxNeverActive} gate can report the
   * count precisely rather than a raw condition error masking it.
   */
  private void awaitAllActive(final List<String> names, final Duration timeout) {
    try {
      cluster
          .when()
          .probe(
              names.size() + " deployments reach ACTIVE",
              () -> names.stream().allMatch(api::isDeploymentActive))
          .await(timeout);
    } catch (final HeimdallConditionError e) {
      // Swallowed on purpose: never-active instances are counted and gated, not thrown here.
    }
  }

  private StartupLatency collectStartupLatency() {
    final Map<String, List<Long>> samples = new LinkedHashMap<>();
    for (final String name : createdDeployments) {
      final long submitEpoch = deploymentSubmitEpoch.getOrDefault(name, 0L);
      for (int index = 0; index < deploymentReplicas.getOrDefault(name, 1); index++) {
        final Map<String, Long> firstSeen = firstEventEpochs(name, index);
        addPhase(samples, "submitToInstalled", firstSeen.get("INSTALLED"), submitEpoch);
        addPhase(
            samples, "installedToResolved", firstSeen.get("RESOLVED"), firstSeen.get("INSTALLED"));
        addPhase(
            samples, "resolvedToStarting", firstSeen.get("STARTING"), firstSeen.get("RESOLVED"));
        addPhase(samples, "startingToActive", firstSeen.get("ACTIVE"), firstSeen.get("STARTING"));
        addPhase(samples, "submitToActive", firstSeen.get("ACTIVE"), submitEpoch);
      }
    }
    final Map<String, Percentiles> phases = new LinkedHashMap<>();
    samples.forEach((phase, values) -> phases.put(phase, Percentiles.of(values)));
    return new StartupLatency(phases);
  }

  private Map<String, Long> firstEventEpochs(final String name, final int index) {
    final Map<String, Long> firstSeen = new LinkedHashMap<>();
    for (final Map<String, Object> event : api.instanceEvents(name, index)) {
      if (!(event.get("kind") instanceof String kind)
          || !(event.get("occurredAtEpochMilli") instanceof Number epoch)) {
        continue;
      }
      firstSeen.merge(kind, epoch.longValue(), Math::min);
    }
    return firstSeen;
  }

  private static void addPhase(
      final Map<String, List<Long>> samples, final String phase, final Long end, final Long begin) {
    if (end == null || begin == null || begin == 0L) {
      return;
    }
    final long delta = end - begin;
    if (delta >= 0) {
      samples.computeIfAbsent(phase, k -> new ArrayList<>()).add(delta);
    }
  }

  private PlacementSpread collectPlacementSpread() {
    final Map<String, Integer> perNode = new LinkedHashMap<>();
    final Map<String, Integer> perWorker = new LinkedHashMap<>();
    for (final String name : createdDeployments) {
      for (final ControlPlaneClient.InstancePlacement placement : api.placements(name)) {
        perNode.merge(placement.nodeId(), 1, Integer::sum);
        cluster
            .workerFor(name, placement.instanceIndex())
            .ifPresent(handle -> perWorker.merge("worker-" + handle.pid(), 1, Integer::sum));
      }
    }
    return new PlacementSpread(perNode, perWorker);
  }

  private FailureCounts collectFailures() {
    int neverActive = 0;
    int failedInstances = 0;
    for (final String name : createdDeployments) {
      final int desired = deploymentReplicas.getOrDefault(name, 1);
      neverActive += Math.max(0, desired - api.activeInstanceCount(name));
      for (final ControlPlaneClient.InstancePlacement placement : api.placements(name)) {
        if ("FAILED".equals(placement.lifecycleState())) {
          failedInstances++;
        }
      }
    }
    return new FailureCounts(failedSubmissions, neverActive, failedInstances);
  }

  private List<MetricSeries> skippedMuninn() {
    // Muninn window series are not implemented yet; recorded as skipped-with-reason so the
    // measurement is never silently absent from the report.
    return List.of(
        new MetricSeries(
            "muninnWindow",
            "",
            Optional.of("Muninn window metrics collection is not implemented in this build"),
            List.<MetricPoint>of()));
  }

  private List<GateOutcome> evaluateGates(
      final FailureCounts failures, final Optional<StartupLatency> startup) {
    final List<GateOutcome> gates = new ArrayList<>();
    final SurtrWorkload.Gates spec = workload.gates();
    gates.add(
        new GateOutcome(
            "maxFailedSubmissions",
            spec.maxFailedSubmissions(),
            failures.failedSubmissions(),
            failures.failedSubmissions() <= spec.maxFailedSubmissions()));
    gates.add(
        new GateOutcome(
            "maxNeverActive",
            spec.maxNeverActive(),
            failures.neverActive(),
            failures.neverActive() <= spec.maxNeverActive()));
    spec.latency()
        .forEach(
            (name, ceiling) -> {
              final long observed = resolveLatencyGate(name, startup);
              gates.add(new GateOutcome(name, ceiling, observed, observed <= ceiling));
            });
    return gates;
  }

  private long resolveLatencyGate(final String name, final Optional<StartupLatency> startup) {
    final Percentiles active =
        startup
            .map(s -> s.phases().get("submitToActive"))
            .orElseThrow(
                () ->
                    new RagnarokException(
                        "latency gate " + name + " needs instanceStartupLatency to be measured"));
    return switch (name) {
      case "instanceActiveP99Millis" -> active.p99();
      case "instanceActiveP95Millis" -> active.p95();
      case "instanceActiveMaxMillis" -> active.max();
      default -> throw new RagnarokException("unknown latency gate: " + name);
    };
  }

  private void gc() {
    for (final String name : List.copyOf(createdDeployments)) {
      deleteDeployment(name);
    }
    for (final String tenant : List.copyOf(createdTenants)) {
      try {
        api.deleteTenant(tenant);
      } catch (final RuntimeException e) {
        // Best-effort teardown: a cleanup miss must not mask the run's own verdict, whatever
        // unchecked type the underlying ControlPlaneClient implementation happens to throw.
      }
    }
  }

  private List<String> deploymentsOfJob(final String jobName) {
    // Every deployment a create job produced shares the job name in its {{job}}-expanded name; but
    // Surtr tracks creations globally, so a churn/delete targeting one job operates on all created
    // deployments in practice. Kept simple deliberately -- workloads run one create job.
    workload
        .jobNamed(jobName)
        .orElseThrow(() -> new RagnarokException("no such target job: " + jobName));
    return createdDeployments;
  }

  private List<String> sample(final List<String> population, final int percent) {
    final List<String> shuffled = new ArrayList<>(population);
    java.util.Collections.shuffle(shuffled, churnRng);
    final int count = Math.max(1, population.size() * percent / 100);
    return new ArrayList<>(shuffled.subList(0, Math.min(count, shuffled.size())));
  }

  private int effectiveIterations(final SurtrJob job) {
    if (job.type() != SurtrJob.Type.CREATE) {
      return 0;
    }
    return Math.max(1, (int) Math.round(job.iterations() * scale));
  }

  private boolean wants(final Measurement measurement) {
    return workload.measurements().contains(measurement);
  }

  private void record(
      final String endpoint, final String verb, final long beginNanos, final int status) {
    apiCalls.add(
        new ApiCall(endpoint, verb, (System.nanoTime() - beginNanos) / 1_000_000L, status));
  }
}
