package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.store.StateStore;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.tenant.Tenant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuously re-derives each tenant's current total assigned resources and marks a deployment's
 * status as quota-violating if a quota was retroactively lowered below what's already running
 * (Phase 5 design §5.2). Level-triggered like every other reconciler: every tick recomputes every
 * tenant's usage from scratch against {@link StateStore#listDeployments}, rather than tracking
 * deltas since last tick, so a quota edit, a deployment edit, or a fresh empty store all converge
 * through the same code path.
 *
 * <p>Deliberately does <b>not</b> evict instances to force compliance -- an even more
 * consequential, unrequested action than Phase 4's own "no automatic rollback" call for a stalled
 * rolling update (Phase 4 design §9); a human operator resolves an over-quota tenant explicitly,
 * this reconciler only surfaces it via {@link StateStore#putQuotaViolation}, read by the API
 * server's deployment status surface.
 */
public final class QuotaReconciler {

  private static final Logger log = LoggerFactory.getLogger(QuotaReconciler.class);

  private final StateStore store;

  public QuotaReconciler(StateStore store) {
    this.store = store;
  }

  public void reconcileOnce() {
    Map<String, TenantUsage.Usage> usageByTenant = new HashMap<>();
    for (DeploymentSpec spec : store.listDeployments()) {
      spec.tenantId()
          .ifPresent(
              tenantId -> {
                TenantUsage.Usage contribution = TenantUsage.contributionOf(store, spec);
                usageByTenant.merge(
                    tenantId,
                    contribution,
                    (a, b) ->
                        new TenantUsage.Usage(
                            a.memoryBytes() + b.memoryBytes(),
                            a.cpuMillicores() + b.cpuMillicores(),
                            a.instances() + b.instances()));
              });
    }

    for (DeploymentSpec spec : store.listDeployments()) {
      Boolean[] violating = {Boolean.FALSE};
      spec.tenantId()
          .ifPresent(
              tenantId -> {
                Tenant tenant = store.getTenant(tenantId).orElse(null);
                if (tenant == null) {
                  return; // an unregistered tenantId is a manifest-validity concern, not this
                  // reconciler's
                }
                TenantUsage.Usage usage =
                    usageByTenant.getOrDefault(tenantId, new TenantUsage.Usage(0, 0, 0));
                if (usage.exceeds(tenant.quota())) {
                  violating[0] = Boolean.TRUE;
                  log.warn(
                      "tenant {} exceeds its quota (memoryBytes={}/{}, cpuMillicores={}/{},"
                          + " instances={}/{}); deployment {} marked quota-violating",
                      tenantId,
                      usage.memoryBytes(),
                      tenant.quota().maxMemoryBytes(),
                      usage.cpuMillicores(),
                      tenant.quota().maxCpuMillicores(),
                      usage.instances(),
                      tenant.quota().maxInstances(),
                      spec.name());
                }
              });
      store.putQuotaViolation(spec.name(), violating[0]);
    }
  }
}
