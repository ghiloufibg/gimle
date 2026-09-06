package com.gimle.controlplane.admission;

import com.gimle.core.config.ConfigEntry;
import com.gimle.mimir.manifest.DeploymentSpec;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A policy-as-data admission plugin: reads simple organization-specific rules out of the same
 * tenant-scoped {@code /config/*} store {@code gimle set config <tenantId> <key> <value>} already
 * writes to (see {@code Multi-tenancy}), rather than a new schema or a dynamic/webhook-based plugin
 * registry. Today's one rule, {@link #MAX_REPLICAS_PER_DEPLOYMENT_KEY}: an operator-set ceiling on
 * how many replicas any single deployment for that tenant may declare -- independent of {@link
 * TenantQuotaPlugin}'s own aggregate resource-quota check, since this is a per-deployment sizing
 * rule, not a billing one. Absent the config entry (the common case: no policy configured for that
 * tenant), every submission is allowed -- policy is opt-in per tenant, per key, not a default
 * restriction every tenant starts under.
 *
 * <p>The presence of that config entry is the only thing that decides here; the tenant's name is
 * never consulted. Writing the key against the {@code default} tenant is as deliberate an act as
 * writing it against a named one, and a rule an operator took the trouble to store has to bind the
 * workloads it names.
 */
public final class PolicyConfigPlugin implements AdmissionPlugin<DeploymentSpec> {

  static final String MAX_REPLICAS_PER_DEPLOYMENT_KEY = "policy.maxReplicasPerDeployment";

  @Override
  public AdmissionDecision<DeploymentSpec> review(AdmissionRequest<DeploymentSpec> request) {
    DeploymentSpec spec = request.spec();
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<ConfigEntry> rule =
        request.store().listConfigEntriesFor(tenantId).stream()
            .filter(entry -> entry.key().equals(MAX_REPLICAS_PER_DEPLOYMENT_KEY))
            .findFirst();
    if (rule.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (rule.get().encrypted()) {
      // A policy rule is plain config, never a secret -- this plugin has no Fafnir access to
      // decrypt one, and a policy value has no business being encrypted in the first place.
      return AdmissionDecision.reject(
          "policy rule '"
              + MAX_REPLICAS_PER_DEPLOYMENT_KEY
              + "' for tenant "
              + tenantId
              + " must not be an encrypted config entry");
    }
    int maxReplicas;
    try {
      maxReplicas = Integer.parseInt(new String(rule.get().value(), StandardCharsets.UTF_8).trim());
    } catch (NumberFormatException e) {
      // Fail closed rather than silently ignore a malformed policy value -- the same "can't
      // verify, so reject" posture TenantQuotaPlugin already takes for an unreadable artifact.
      return AdmissionDecision.reject(
          "policy rule '"
              + MAX_REPLICAS_PER_DEPLOYMENT_KEY
              + "' for tenant "
              + tenantId
              + " is not a valid integer");
    }
    if (spec.replicas() > maxReplicas) {
      return AdmissionDecision.reject(
          "deployment "
              + spec.name()
              + " requests "
              + spec.replicas()
              + " replicas, exceeding tenant "
              + tenantId
              + "'s policy ceiling of "
              + maxReplicas
              + " ("
              + MAX_REPLICAS_PER_DEPLOYMENT_KEY
              + ")");
    }
    return AdmissionDecision.allow(spec);
  }
}
