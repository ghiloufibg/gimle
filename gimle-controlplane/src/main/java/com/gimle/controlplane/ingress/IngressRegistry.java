package com.gimle.controlplane.ingress;

import com.gimle.mimir.manifest.IngressSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Every declared {@link IngressSpec}, persisted through {@code gimle-mimir} exactly the way {@code
 * NetworkPolicyRegistry} persists a policy: an Ingress declared against one control-plane replica
 * is visible to every other replica reading the same store cluster and survives a restart. This
 * class holds no spec state of its own -- reads go straight to the store, writes go through the
 * guarded path below.
 *
 * <p>Writes are lease-guarded read-check-propose with the before-read routed to the leader, so a
 * lagging replica cannot make a stale {@code expectedVersion} look current. Two operators editing
 * one Ingress's route list at the same time either serialize cleanly or the loser is told its
 * version is stale; neither silently overwrites the other, which is the whole reason routes move
 * out of a flat config string in the first place -- a config value has no version to compare.
 */
public final class IngressRegistry {

  private static final int MAX_WRITE_ATTEMPTS = 50;
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);

  private final StoreClient storeClient;

  public IngressRegistry(StoreClient storeClient) {
    this.storeClient = storeClient;
  }

  /**
   * Full replace. {@code expectedVersion} absent means an unconditional overwrite -- the shape a
   * first declaration uses, where there is no version to have an opinion about yet.
   */
  public IngressWriteResult put(IngressSpec spec, OptionalInt expectedVersion) {
    String leaseName = "ingress/" + spec.tenantId() + "/" + spec.name();
    // Fresh per call: two concurrent writers must present distinct holder identities, or the
    // store's own "already held by this holderId" renewal rule would let both hold it at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue;
      }
      try {
        Optional<IngressSpec> before =
            storeClient.getIngressLinearizable(spec.tenantId(), spec.name());
        int currentVersion = before.map(IngressSpec::version).orElse(0);
        if (expectedVersion.isPresent() && expectedVersion.getAsInt() != currentVersion) {
          // Immediate, no retry: a stale expectedVersion is the caller's to resolve against the
          // state carried back here, not something this registry can guess its way past.
          return new IngressWriteResult.VersionConflict(currentVersion, before);
        }
        IngressSpec stamped = spec.withVersion(currentVersion + 1);
        storeClient.propose(new StateMutation.PutIngress(stamped));
        return new IngressWriteResult.Written(stamped);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new IngressWriteResult.Contended();
  }

  public Optional<IngressSpec> get(String tenantId, String name) {
    return storeClient.getIngress(tenantId, name);
  }

  public List<IngressSpec> list() {
    return storeClient.listIngresses();
  }

  public void remove(String tenantId, String name) {
    storeClient.propose(new StateMutation.RemoveIngress(tenantId, name));
  }
}
