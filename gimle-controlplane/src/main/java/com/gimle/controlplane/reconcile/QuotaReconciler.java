package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuously re-derives each tenant's current total assigned resources and marks a workload's
 * status as quota-violating if a quota was retroactively lowered below what's already running.
 * Level-triggered like every other reconciler: every tick recomputes every tenant's usage from
 * scratch against every placeable workload kind (Deployment, Job, DaemonSet, StatefulSet -- see
 * {@link TenantUsage}'s own javadoc for why all four, not Deployment alone), rather than tracking
 * deltas since last tick, so a quota edit, a workload edit, or a fresh empty store all converge
 * through the same code path.
 *
 * <p>Deliberately does <b>not</b> evict instances to force compliance -- an even more
 * consequential, unrequested action than leaving a stalled rolling update unrolled-back; a human
 * operator resolves an over-quota tenant explicitly, this reconciler only surfaces it via {@link
 * StateStore#putQuotaViolation}, read by the API server's deployment status surface.
 */
public final class QuotaReconciler {

  private static final Logger log = LoggerFactory.getLogger(QuotaReconciler.class);

  private final StoreReader store;
  private final MutationSink mutations;
  private final ArtifactResolver artifactResolver;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public QuotaReconciler(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public QuotaReconciler(StoreReader store, MutationSink mutations) {
    this(store, mutations, ArtifactResolver.localOnly());
  }

  public QuotaReconciler(
      StoreReader store, MutationSink mutations, ArtifactResolver artifactResolver) {
    this.store = store;
    this.mutations = mutations;
    this.artifactResolver = artifactResolver;
  }

  public void reconcileOnce() {
    List<WorkloadSpec> workloads = new ArrayList<>();
    workloads.addAll(store.listDeployments());
    workloads.addAll(store.listJobSpecs());
    workloads.addAll(store.listDaemonSetSpecs());
    workloads.addAll(store.listStatefulSetSpecs());

    Map<String, TenantUsage.Usage> usageByTenant = new HashMap<>();
    for (WorkloadSpec spec : workloads) {
      try {
        accumulateUsage(usageByTenant, spec);
      } catch (RuntimeException e) {
        // One workload's usage computation failing (e.g. an unresolvable artifact reference) must
        // never abort the rest of this tick's usage accumulation -- the next tick retries from the
        // same full snapshot.
        log.warn(
            "quota usage accumulation for {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }

    for (WorkloadSpec spec : workloads) {
      try {
        reconcileQuotaViolation(spec, usageByTenant);
      } catch (RuntimeException e) {
        log.warn("quota violation reconcile for {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void accumulateUsage(Map<String, TenantUsage.Usage> usageByTenant, WorkloadSpec spec) {
    if (!Tenant.isEnforceable(spec.tenantId())) {
      return;
    }
    String tenantId = spec.tenantId().get();
    TenantUsage.Usage contribution = TenantUsage.contributionOf(store, artifactResolver, spec);
    usageByTenant.merge(
        tenantId,
        contribution,
        (a, b) ->
            new TenantUsage.Usage(
                a.memoryBytes() + b.memoryBytes(),
                a.cpuMillicores() + b.cpuMillicores(),
                a.instances() + b.instances()));
  }

  private void reconcileQuotaViolation(
      WorkloadSpec spec, Map<String, TenantUsage.Usage> usageByTenant) {
    boolean violating = false;
    if (Tenant.isEnforceable(spec.tenantId())) {
      String tenantId = spec.tenantId().get();
      Tenant tenant = store.getTenant(tenantId).orElse(null);
      // A null tenant (an unregistered tenantId) is a manifest-validity concern, not this
      // reconciler's -- nothing to check, so it just stays non-violating.
      if (tenant != null) {
        TenantUsage.Usage usage =
            usageByTenant.getOrDefault(tenantId, new TenantUsage.Usage(0, 0, 0));
        if (usage.exceeds(tenant.quota())) {
          violating = true;
          log.warn(
              "tenant {} exceeds its quota (memoryBytes={}/{}, cpuMillicores={}/{},"
                  + " instances={}/{}); {} marked quota-violating",
              tenantId,
              usage.memoryBytes(),
              tenant.quota().maxMemoryBytes(),
              usage.cpuMillicores(),
              tenant.quota().maxCpuMillicores(),
              usage.instances(),
              tenant.quota().maxInstances(),
              spec.name());
        }
      }
    }
    // Level-triggered means recomputing from scratch every tick, not re-proposing every tick:
    // this reconciler still corrects a wrong stored value on its very next run regardless of
    // what happened on prior ticks, but a value that's already correct shouldn't cost a fresh
    // replicated write -- see StateMutation's own javadoc on why heartbeats were kept out of
    // the log entirely for the same reason.
    if (store.isQuotaViolating(spec.tenantId(), spec.name()) != violating) {
      mutations.propose(
          new StateMutation.PutQuotaViolation(spec.tenantId(), spec.name(), violating));
    }
  }
}
