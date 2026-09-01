package com.gimle.controlplane.networkpolicy;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A partial update to one stored {@code NetworkPolicySpec}: only what it names is touched,
 * everything else survives exactly as stored. This is what keeps adding or dropping a single
 * caller tenant from being a client-side get-rebuild-replace of the whole spec, where a field the
 * client forgot to carry over silently widens the policy.
 *
 * <p>The two direction sets are edited by {@code add}/{@code remove} rather than replaced, so two
 * operators editing the same policy's allow list concurrently do not have to serialize on a full
 * document. A direction the stored policy does not restrict at all cannot be edited here: turning
 * "unrestricted" into "only these tenants" is a change of what the policy means, not an adjustment
 * of it, so it is refused and the whole-policy write path used instead.
 *
 * <p>The two scoping sets replace wholesale, since there is no meaningful incremental edit of
 * "which deployments does this cover": {@code Optional.empty()} leaves the stored scoping alone, a
 * present set replaces it, and a present <em>empty</em> set widens the policy back to the whole
 * tenant.
 */
public record NetworkPolicyPatch(
    Set<String> addAllowedCallerTenantIds,
    Set<String> removeAllowedCallerTenantIds,
    Set<String> addAllowedCalleeTenantIds,
    Set<String> removeAllowedCalleeTenantIds,
    Optional<Set<String>> deploymentNames,
    Optional<Set<String>> serviceInterfaceNames) {

  public NetworkPolicyPatch {
    addAllowedCallerTenantIds = Set.copyOf(requireNonNull(addAllowedCallerTenantIds, "add callers"));
    removeAllowedCallerTenantIds =
        Set.copyOf(requireNonNull(removeAllowedCallerTenantIds, "remove callers"));
    addAllowedCalleeTenantIds = Set.copyOf(requireNonNull(addAllowedCalleeTenantIds, "add callees"));
    removeAllowedCalleeTenantIds =
        Set.copyOf(requireNonNull(removeAllowedCalleeTenantIds, "remove callees"));
    if (deploymentNames == null) {
      throw new IllegalArgumentException("deploymentNames must be Optional.empty(), not null");
    }
    if (serviceInterfaceNames == null) {
      throw new IllegalArgumentException(
          "serviceInterfaceNames must be Optional.empty(), not null");
    }
    deploymentNames = deploymentNames.map(Set::copyOf);
    serviceInterfaceNames = serviceInterfaceNames.map(Set::copyOf);
  }

  /** Whether this patch would change anything at all -- an empty one is a caller mistake. */
  public boolean isEmpty() {
    return addAllowedCallerTenantIds.isEmpty()
        && removeAllowedCallerTenantIds.isEmpty()
        && addAllowedCalleeTenantIds.isEmpty()
        && removeAllowedCalleeTenantIds.isEmpty()
        && deploymentNames.isEmpty()
        && serviceInterfaceNames.isEmpty();
  }

  /** Every tenant id this patch would add to either direction's allow list. */
  public Set<String> addedTenantIds() {
    Set<String> added = new LinkedHashSet<>(addAllowedCallerTenantIds);
    added.addAll(addAllowedCalleeTenantIds);
    return Set.copyOf(added);
  }

  private static Set<String> requireNonNull(Set<String> value, String what) {
    if (value == null) {
      throw new IllegalArgumentException(what + " must be an empty set, not null");
    }
    return value;
  }
}
