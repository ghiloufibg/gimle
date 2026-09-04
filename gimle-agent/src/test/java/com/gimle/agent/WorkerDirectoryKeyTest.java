package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A log request names an instance by deployment name and index -- the only handle an operator or
 * the control plane has for it. Resolving that to the {@code workers/<key>} directory its files
 * actually live in used to be done by composing the supervision key by hand as {@code
 * deploymentName#instanceIndex}, which stopped matching anything the moment that key became
 * tenant-scoped: every by-name log read answered off a directory that was never created, and for a
 * Tier 1 instance packed onto a sibling's worker there is no such directory even in principle.
 */
class WorkerDirectoryKeyTest {

  private static final ModuleId MODULE =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static SupervisedInstance supervised(
      Optional<String> tenantId, String deploymentName, int instanceIndex, String workerKey) {
    AssignedInstance assigned =
        new AssignedInstance(
            deploymentName, instanceIndex, MODULE, "/does/not/matter.jar", tenantId);
    return new SupervisedInstance(assigned, null, null, null, workerKey, null);
  }

  private static Map<String, SupervisedInstance> node(SupervisedInstance... instances) {
    Map<String, SupervisedInstance> supervised = new HashMap<>();
    for (SupervisedInstance instance : instances) {
      supervised.put(AgentMain.instanceKey(instance.assigned), instance);
    }
    return supervised;
  }

  @Test
  void an_untenanted_instance_resolves_to_the_key_the_agent_actually_filed_it_under() {
    Map<String, SupervisedInstance> node =
        node(supervised(Optional.empty(), "orders-service", 0, null));

    assertEquals(
        AgentMain.instanceKey(Optional.empty(), "orders-service", 0),
        AgentMain.workerDirectoryKey(node, Optional.empty(), "orders-service", 0));
  }

  @Test
  void a_tenanted_instance_resolves_without_the_caller_having_to_name_its_tenant() {
    Map<String, SupervisedInstance> node =
        node(supervised(Optional.of("acme"), "orders-service", 2, null));

    assertEquals(
        AgentMain.instanceKey(Optional.of("acme"), "orders-service", 2),
        AgentMain.workerDirectoryKey(node, Optional.empty(), "orders-service", 2));
  }

  @Test
  void a_density_packed_instance_resolves_to_the_worker_it_was_packed_onto() {
    String ownerKey = AgentMain.instanceKey(Optional.of("acme"), "orders-service", 0);
    Map<String, SupervisedInstance> node =
        node(
            supervised(Optional.of("acme"), "orders-service", 0, ownerKey),
            supervised(Optional.of("acme"), "billing-service", 0, ownerKey));

    assertEquals(
        ownerKey, AgentMain.workerDirectoryKey(node, Optional.empty(), "billing-service", 0));
  }

  @Test
  void a_declared_tenant_picks_between_two_tenants_sharing_a_name_and_index_on_one_node() {
    Map<String, SupervisedInstance> node =
        node(
            supervised(Optional.of("acme"), "orders-service", 0, null),
            supervised(Optional.of("globex"), "orders-service", 0, null));

    assertEquals(
        AgentMain.instanceKey(Optional.of("globex"), "orders-service", 0),
        AgentMain.workerDirectoryKey(node, Optional.of("globex"), "orders-service", 0));
  }

  @Test
  void an_instance_this_node_does_not_host_falls_back_to_a_directory_that_does_not_exist() {
    assertEquals(
        AgentMain.instanceKey(Optional.empty(), "nowhere", 7),
        AgentMain.workerDirectoryKey(Map.of(), Optional.empty(), "nowhere", 7));
  }
}
