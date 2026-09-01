package com.gimle.controlplane.networkpolicy;

import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every declared {@link NetworkPolicySpec}, persisted through {@code gimle-mimir} the same way
 * {@code com.gimle.controlplane.service.ServiceRegistry} persists {@code ServiceSpec} -- a policy
 * created against one control-plane replica is visible to every other replica reading the same
 * store cluster, not just the replica it was submitted to. This class holds no spec state of its
 * own; reads go straight to the store and writes go through the guarded path below.
 *
 * <p>Writes are lease-guarded read-check-propose, the same shape {@code
 * com.gimle.controlplane.configmap.ConfigMapStore} uses: one lease per {@code (tenantId, name)},
 * acquired first and held across the whole read-check-propose sequence, with the before-read routed
 * to the leader so a lagging replica can't make a stale {@code expectedVersion} look current. Two
 * operators editing one policy's allow list at the same time therefore either serialize cleanly or
 * the loser is told its version is stale -- neither silently overwrites the other's edit, which a
 * plain "replace the whole object" API cannot avoid.
 */
public final class NetworkPolicyRegistry {

  // Same bound ConfigMapStore's own write loop uses -- generous on purpose, since a real
  // deployment sees at most a couple of colliding writers, not the fully-simultaneous N-way race a
  // stress test deliberately creates.
  private static final int MAX_WRITE_ATTEMPTS = 50;
  // A policy is a small, fixed-shape document, so this only has to cover a read, a compare, and a
  // propose -- not an arbitrary caller-sized payload.
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);

  private final StoreClient storeClient;

  public NetworkPolicyRegistry(StoreClient storeClient) {
    this.storeClient = storeClient;
  }

  /**
   * Full replace. {@code expectedVersion} absent means an unconditional overwrite -- the shape a
   * first declaration of a policy uses, where there is no version to have an opinion about yet.
   */
  public NetworkPolicyWriteResult put(NetworkPolicySpec spec, OptionalInt expectedVersion) {
    return write(spec.tenantId(), spec.name(), expectedVersion, before -> Optional.of(spec));
  }

  /**
   * Partial update of an existing policy: {@code expectedVersion} is always required (there is no
   * unconditional partial update -- a merge onto an unknown base is exactly the lost update this
   * guard exists to stop), and a name that isn't stored yields {@link
   * NetworkPolicyWriteResult.NotFound} rather than creating one.
   */
  public NetworkPolicyWriteResult patch(
      String tenantId, String name, int expectedVersion, NetworkPolicyPatch patch) {
    return write(
        tenantId, name, OptionalInt.of(expectedVersion), before -> applyPatch(before, patch));
  }

  public Optional<NetworkPolicySpec> get(String tenantId, String name) {
    return storeClient.getNetworkPolicy(tenantId, name);
  }

  public List<NetworkPolicySpec> list() {
    return storeClient.listNetworkPolicies();
  }

  public void remove(String tenantId, String name) {
    storeClient.propose(new StateMutation.RemoveNetworkPolicy(tenantId, name));
  }

  /**
   * The lease-guarded write path both {@link #put} and {@link #patch} run through. {@code
   * computeNewSpec} sees the stored policy (absent if none) and returns the replacement, or empty
   * to report {@link NetworkPolicyWriteResult.NotFound}; the version it is stamped with is minted
   * here, never taken from what the caller submitted.
   */
  private NetworkPolicyWriteResult write(
      String tenantId,
      String name,
      OptionalInt expectedVersion,
      Function<Optional<NetworkPolicySpec>, Optional<NetworkPolicySpec>> computeNewSpec) {
    String leaseName = leaseName(tenantId, name);
    // Fresh per call, not a shared field: two concurrent writers -- exactly the case this lease
    // exists to serialize -- must present distinct holder identities, or the store's own
    // "already held by this holderId" renewal rule would let both hold the lease at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        // Another writer is mid-write for this exact policy; retry once it frees up.
        continue;
      }
      try {
        Optional<NetworkPolicySpec> before =
            storeClient.getNetworkPolicyLinearizable(tenantId, name);
        int currentVersion = before.map(NetworkPolicySpec::version).orElse(0);
        if (expectedVersion.isPresent() && expectedVersion.getAsInt() != currentVersion) {
          // Immediate, no retry: a stale expectedVersion is the caller's to resolve against the
          // state carried back here, not something this registry can guess its way past.
          return new NetworkPolicyWriteResult.VersionConflict(currentVersion, before);
        }
        Optional<NetworkPolicySpec> merged = computeNewSpec.apply(before);
        if (merged.isEmpty()) {
          return new NetworkPolicyWriteResult.NotFound();
        }
        NetworkPolicySpec stamped = merged.get().withVersion(currentVersion + 1);
        storeClient.propose(new StateMutation.PutNetworkPolicy(stamped));
        return new NetworkPolicyWriteResult.Written(stamped);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new NetworkPolicyWriteResult.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /**
   * Merges {@code patch} onto the stored policy. Empty when nothing is stored under the name, which
   * the caller reports as {@link NetworkPolicyWriteResult.NotFound}. Editing a direction the stored
   * policy does not restrict at all is refused outright: adding an allowed caller to an
   * ingress-unrestricted policy would turn "anyone may call in" into "only this one tenant may,"
   * which is a redeclaration of the policy rather than an adjustment to it.
   */
  private static Optional<NetworkPolicySpec> applyPatch(
      Optional<NetworkPolicySpec> before, NetworkPolicyPatch patch) {
    if (before.isEmpty()) {
      return Optional.empty();
    }
    NetworkPolicySpec current = before.get();
    Optional<Set<String>> callers =
        edited(
            current.allowedCallerTenantIds(),
            patch.addAllowedCallerTenantIds(),
            patch.removeAllowedCallerTenantIds(),
            "allowedCallerTenantIds");
    Optional<Set<String>> callees =
        edited(
            current.allowedCalleeTenantIds(),
            patch.addAllowedCalleeTenantIds(),
            patch.removeAllowedCalleeTenantIds(),
            "allowedCalleeTenantIds");
    return Optional.of(
        new NetworkPolicySpec(
            current.name(),
            current.tenantId(),
            replacedScoping(current.deploymentNames(), patch.deploymentNames()),
            replacedScoping(current.serviceInterfaceNames(), patch.serviceInterfaceNames()),
            callers,
            callees,
            current.version()));
  }

  private static Optional<Set<String>> edited(
      Optional<Set<String>> currentDirection,
      Set<String> added,
      Set<String> removed,
      String field) {
    if (added.isEmpty() && removed.isEmpty()) {
      return currentDirection;
    }
    if (currentDirection.isEmpty()) {
      throw new IllegalArgumentException(
          "this policy does not restrict "
              + field
              + " at all; declare the direction with a full write before editing it");
    }
    Set<String> edited = new LinkedHashSet<>(currentDirection.get());
    edited.addAll(added);
    edited.removeAll(removed);
    return Optional.of(edited);
  }

  /** A present set replaces the stored scoping; a present empty set widens back to unscoped. */
  private static Optional<Set<String>> replacedScoping(
      Optional<Set<String>> currentScoping, Optional<Set<String>> replacement) {
    if (replacement.isEmpty()) {
      return currentScoping;
    }
    return replacement.get().isEmpty() ? Optional.empty() : replacement;
  }

  private static String leaseName(String tenantId, String name) {
    return "networkpolicy-write:" + tenantId + ":" + name;
  }
}
