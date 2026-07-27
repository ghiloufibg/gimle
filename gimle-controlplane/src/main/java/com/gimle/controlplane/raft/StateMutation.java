package com.gimle.controlplane.raft;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;

/**
 * Every mutating operation {@link StateStore} exposes, replicated through the Raft log (design
 * §2.1) -- one variant per {@code StateStore} method that changes durable state, dispatched by
 * {@link RaftNode#apply} via {@link #applyTo}. {@code putNodeHeartbeat} deliberately has no variant
 * here: heartbeats are high-frequency, tolerate a brief gap after a leader change, and would make
 * the log's write rate scale with cluster size for no correctness benefit (design §2.1) -- only the
 * leader's own {@code StateStore} ever receives them, outside the log entirely.
 */
public sealed interface StateMutation {

  void applyTo(StateStore store);

  record PutDeployment(DeploymentSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putDeployment(spec);
    }
  }

  record RemoveDeployment(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeDeployment(name);
    }
  }

  record PutAssignment(InstanceAssignment assignment) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putAssignment(assignment);
    }
  }

  record RemoveAssignment(String deploymentName, int instanceIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeAssignment(deploymentName, instanceIndex);
    }
  }

  record PutRollingIndex(String deploymentName, int instanceIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRollingIndex(deploymentName, instanceIndex);
    }
  }

  record ClearRollingIndex(String deploymentName) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.clearRollingIndex(deploymentName);
    }
  }

  record PutEffectiveReplicas(String deploymentName, int replicas) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putEffectiveReplicas(deploymentName, replicas);
    }
  }

  record PutNodeRegistration(NodeRegistration registration) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putNodeRegistration(registration);
    }
  }

  record PutTenant(Tenant tenant) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putTenant(tenant);
    }
  }

  record RemoveTenant(String id) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeTenant(id);
    }
  }

  record PutQuotaViolation(String deploymentName, boolean violating) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putQuotaViolation(deploymentName, violating);
    }
  }

  record PutConfigEntry(ConfigEntry entry) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putConfigEntry(entry);
    }
  }

  record RemoveConfigEntry(String tenantId, String key) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeConfigEntry(tenantId, key);
    }
  }
}
