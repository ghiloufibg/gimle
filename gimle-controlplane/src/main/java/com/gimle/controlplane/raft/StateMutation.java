package com.gimle.controlplane.raft;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.authz.Account;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;

/**
 * Every mutating operation {@link StateStore} exposes, replicated through the Raft log -- one
 * variant per {@code StateStore} method that changes durable state, applied to the store via {@link
 * #applyTo} once a {@link RaftNode} commits the entry. {@code putNodeHeartbeat} deliberately has no
 * variant here: heartbeats are high-frequency, tolerate a brief gap after a leader change, and
 * would make the log's write rate scale with cluster size for no correctness benefit -- only the
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

  // Fully qualified deliberately: this package already declares its own Role (a Raft node's
  // FOLLOWER/CANDIDATE/LEADER state), which shadows an unqualified single-type-import of the RBAC
  // com.gimle.core.authz.Role of the same simple name -- same-package types always win Java's
  // unqualified-name resolution over an import, silently, with no compile error at the declaration
  // site (only at first attempted use of the wrong type, e.g. `new Role(...)`).
  record PutRole(com.gimle.core.authz.Role role) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRole(role);
    }
  }

  record RemoveRole(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRole(name);
    }
  }

  record PutRoleBinding(RoleBinding binding) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRoleBinding(binding);
    }
  }

  record RemoveRoleBinding(String id) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRoleBinding(id);
    }
  }

  record PutAccount(Account account) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putAccount(account);
    }
  }

  record RemoveAccount(String username) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeAccount(username);
    }
  }
}
