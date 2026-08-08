package com.gimle.mimir.store;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import java.util.List;
import java.util.Optional;

/**
 * Every read method {@code ApiServer}, the five reconcilers, and {@code Authorizer} call outside
 * this package -- implemented by both {@link StateStore} (a reconciler/{@code Authorizer} unit
 * test's fast, network-free fixture, unchanged since before the etcd-store-extraction) and {@code
 * com.gimle.mimir.rpc.StoreClient} (what those same classes are constructed with in production).
 * Extracted once both needed to be interchangeable behind one field: without this interface,
 * "{@code StoreClient} mirrors {@code StateStore}'s method signatures" and "reconciler tests keep
 * constructing a plain {@code StateStore}" can't both be true for the same statically-typed field.
 * Deliberately excludes every write ({@code putNodeHeartbeat} included) -- those go through {@link
 * com.gimle.mimir.raft.MutationSink}/{@code StoreClient}'s own {@code putHeartbeat}, never through
 * a plain read interface.
 */
public interface StoreReader {

  List<Account> listAccounts();

  Optional<Tenant> getTenant(String id);

  Optional<DeploymentSpec> getDeployment(String name);

  List<DeploymentSpec> listDeployments();

  List<InstanceAssignment> listAssignmentsFor(String deploymentName);

  boolean isQuotaViolating(String deploymentName);

  List<InstanceAssignment> listAssignments();

  List<NodeRegistration> listNodeRegistrations();

  List<Tenant> listTenants();

  List<ConfigEntry> listConfigEntriesFor(String tenantId);

  List<Role> listRoles();

  Optional<Role> getRole(String name);

  List<RoleBinding> listRoleBindings();

  Optional<RoleBinding> getRoleBinding(String id);

  Optional<Account> getAccount(String username);

  Optional<NodeRegistration> getNodeRegistration(String nodeId);

  Optional<Integer> getEffectiveReplicas(String deploymentName);

  Optional<Integer> getRollingIndex(String deploymentName);

  Optional<ObservedHeartbeat> getNodeHeartbeat(String nodeId);

  Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      String deploymentName, int instanceIndex);

  List<ReconcilerInstanceState> listReconcilerInstanceStates();
}
