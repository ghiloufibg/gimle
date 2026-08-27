package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.ragnarok.target.ControlPlaneClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Steps exercising state-store mechanisms that sit alongside the replicated workload/tenant data:
 * RBAC persistence, leader-local leases and node heartbeats (both deliberately outside the Raft
 * log), the per-instance event log's retention cap, the cluster-wide audit trail, and a
 * StatefulSet's sticky ordinal-to-node binding.
 */
public final class StateStoreSteps {

  private final ScenarioWorld world;

  public StateStoreSteps(final ScenarioWorld world) {
    this.world = world;
  }

  // ---- RBAC persistence ----

  @When("role {string} granting {string} {string} is created")
  public void roleGrantingIsCreated(final String name, final String resource, final String verb) {
    world.cluster().api().putRole(name, resource, verb);
  }

  @When("role binding {string} binds subject {string} to role {string}")
  public void roleBindingBindsSubjectToRole(
      final String id, final String subject, final String roleName) {
    world.cluster().api().putRoleBinding(id, subject, roleName);
  }

  @When("account {string} with password {string} is created")
  public void accountWithPasswordIsCreated(final String username, final String password) {
    world.cluster().api().putAccount(username, password);
  }

  @Then("role {string} is readable")
  public void roleIsReadable(final String name) {
    assertTrue(world.cluster().api().roleExists(name), "role " + name + " is not readable");
  }

  @Then("role binding {string} is readable")
  public void roleBindingIsReadable(final String id) {
    assertTrue(
        world.cluster().api().roleBindingExists(id), "role binding " + id + " is not readable");
  }

  @Then("account {string} is readable")
  public void accountIsReadable(final String username) {
    assertTrue(
        world.cluster().api().accountExists(username), "account " + username + " is not readable");
  }

  // ---- leader-local leases ----

  @When("holder {string} acquires lease {string} for {int}s")
  public void holderAcquiresLeaseFor(
      final String holderId, final String leaseName, final int ttlSeconds) {
    try (StoreClient client = world.cluster().openStoreClient()) {
      final LeaseGrant grant =
          client.tryAcquireOrRenewLease(leaseName, holderId, Duration.ofSeconds(ttlSeconds));
      world.lastLeaseGranted = grant.granted();
    }
  }

  @When("holder {string} tries to acquire lease {string} for {int}s")
  public void holderTriesToAcquireLeaseFor(
      final String holderId, final String leaseName, final int ttlSeconds) {
    holderAcquiresLeaseFor(holderId, leaseName, ttlSeconds);
  }

  @Then("the lease acquisition succeeds")
  public void theLeaseAcquisitionSucceeds() {
    requireLeaseOutcome();
    assertTrue(world.lastLeaseGranted, "the lease acquisition was denied, expected it to succeed");
  }

  @Then("the lease acquisition is denied")
  public void theLeaseAcquisitionIsDenied() {
    requireLeaseOutcome();
    assertFalse(
        world.lastLeaseGranted, "the lease acquisition succeeded, expected it to be denied");
  }

  /**
   * The TTL-expiry half: retried on the real Heimdall probe loop (not a single fixed sleep) since
   * exactly how long the first holder's grant takes to expire is only bounded, not exact.
   */
  @Then("within {int}s holder {string} acquires lease {string} for {int}s")
  public void withinSecondsHolderAcquiresLeaseFor(
      final int seconds, final String holderId, final String leaseName, final int ttlSeconds) {
    world
        .cluster()
        .when()
        .probe(
            "holder " + holderId + " acquires lease " + leaseName + " once it expires",
            () -> {
              try (StoreClient client = world.cluster().openStoreClient()) {
                final boolean granted =
                    client
                        .tryAcquireOrRenewLease(leaseName, holderId, Duration.ofSeconds(ttlSeconds))
                        .granted();
                if (granted) {
                  world.lastLeaseGranted = true;
                }
                return granted;
              }
            })
        .await(Duration.ofSeconds(seconds));
  }

  private void requireLeaseOutcome() {
    if (world.lastLeaseGranted == null) {
      throw new HolmgangException("no lease acquisition was attempted in this scenario");
    }
  }

  // ---- node heartbeats ----

  @Then("within {int}s node {string} has reported a heartbeat")
  public void withinSecondsNodeHasReportedAHeartbeat(final int seconds, final String nodeId) {
    world
        .cluster()
        .when()
        .probe(
            "node " + nodeId + " has reported a heartbeat",
            () -> world.cluster().api().nodeLastHeartbeatAt(nodeId).isPresent())
        .await(Duration.ofSeconds(seconds));
  }

  @Then("within {int}s node {string}'s heartbeat advances")
  public void withinSecondsNodeHeartbeatAdvances(final int seconds, final String nodeId) {
    final String baseline =
        world
            .cluster()
            .api()
            .nodeLastHeartbeatAt(nodeId)
            .orElseThrow(
                () ->
                    new HolmgangException("node " + nodeId + " has not yet reported a heartbeat"));
    world
        .cluster()
        .when()
        .probe(
            "node " + nodeId + "'s heartbeat advances past " + baseline,
            () ->
                world
                    .cluster()
                    .api()
                    .nodeLastHeartbeatAt(nodeId)
                    .filter(at -> !at.equals(baseline))
                    .isPresent())
        .await(Duration.ofSeconds(seconds));
  }

  // ---- per-instance event log retention ----

  @When(
      "{int} synthetic lifecycle events are relayed for instance {int} of {string} on node {string}")
  public void syntheticLifecycleEventsAreRelayedForInstanceOfOnNode(
      final int count, final int instanceIndex, final String deployment, final String nodeId) {
    final ClusterApi api = world.cluster().api();
    final long base = System.currentTimeMillis();
    for (int i = 0; i < count; i++) {
      api.postInstanceEvent(
          nodeId,
          "synthetic-" + deployment + "-" + instanceIndex + "-" + i,
          deployment,
          instanceIndex,
          "STARTING",
          "synthetic retention-cap event " + i,
          base + i);
    }
  }

  @Then("instance {int} of {string} has exactly {int} recorded events, newest first")
  public void instanceHasExactlyRecordedEventsNewestFirst(
      final int instanceIndex, final String deployment, final int expectedCount) {
    final List<Map<String, Object>> events =
        world.cluster().api().instanceEvents(deployment, instanceIndex);
    assertEquals(
        expectedCount,
        events.size(),
        "instance " + instanceIndex + " of " + deployment + " has " + events.size() + " events");
    for (int i = 1; i < events.size(); i++) {
      final long previous = epochMilliOf(events.get(i - 1));
      final long current = epochMilliOf(events.get(i));
      assertTrue(
          previous >= current,
          "events are not newest-first: index "
              + (i - 1)
              + " ("
              + previous
              + ") precedes index "
              + i
              + " ("
              + current
              + ")");
    }
  }

  private static long epochMilliOf(final Map<String, Object> event) {
    return ((Number) event.get("occurredAtEpochMilli")).longValue();
  }

  // ---- cluster-wide audit trail ----

  @Then("the audit trail for tenant {string} contains a {string} entry")
  public void theAuditTrailForTenantContainsAnEntry(
      final String tenantId, final String resourceKind) {
    final List<Map<String, Object>> events =
        world.cluster().api().auditEvents(Optional.of(resourceKind), Optional.of(tenantId));
    assertTrue(
        !events.isEmpty(),
        "no audit entry for resource " + resourceKind + " and tenant " + tenantId);
  }

  @Then("the audit trail for tenant {string} excludes tenant {string}")
  public void theAuditTrailForTenantExcludesTenant(
      final String tenantId, final String otherTenantId) {
    final List<Map<String, Object>> events =
        world.cluster().api().auditEvents(Optional.empty(), Optional.of(tenantId));
    for (final Map<String, Object> event : events) {
      assertFalse(
          otherTenantId.equals(event.get("tenantId")),
          "audit filter for tenant " + tenantId + " leaked an event from " + otherTenantId);
    }
  }

  // ---- StatefulSet sticky node binding ----

  @Given(
      "statefulset {string} with {int} replica(s) of provider {string} version {string} is deployed")
  public void statefulsetWithReplicasOfProviderVersionIsDeployed(
      final String name, final int replicas, final String artifact, final String version) {
    world
        .cluster()
        .api()
        .submitStatefulSet(
            name,
            ExampleModules.moduleName(artifact),
            version,
            ExampleModules.jar(artifact),
            replicas,
            Optional.empty());
    world.statefulSets.add(name);
  }

  @Then("within {int}s statefulset {string} index {int} is ACTIVE")
  public void withinSecondsStatefulsetIndexIsActive(
      final int seconds, final String name, final int index) {
    world
        .cluster()
        .when()
        .probe(
            "statefulset " + name + " index " + index + " is ACTIVE",
            () ->
                world.cluster().api().statefulSetPlacements(name).stream()
                    .anyMatch(
                        p -> p.instanceIndex() == index && "ACTIVE".equals(p.lifecycleState())))
        .await(Duration.ofSeconds(seconds));
  }

  @Given("the node currently hosting statefulset {string} index {int} is remembered")
  public void theNodeCurrentlyHostingStatefulsetIndexIsRemembered(
      final String name, final int index) {
    world.rememberedNodeId = nodeHosting(name, index);
  }

  @Then("statefulset {string} index {int} is rebound to the same node")
  public void statefulsetIndexIsReboundToTheSameNode(final String name, final int index) {
    if (world.rememberedNodeId == null) {
      throw new HolmgangException("no node was remembered for " + name + " index " + index);
    }
    assertEquals(
        world.rememberedNodeId,
        nodeHosting(name, index),
        "statefulset " + name + " index " + index + " was rescheduled onto a different node");
  }

  private String nodeHosting(final String name, final int index) {
    return world.cluster().api().statefulSetPlacements(name).stream()
        .filter(p -> p.instanceIndex() == index)
        .findFirst()
        .map(ControlPlaneClient.InstancePlacement::nodeId)
        .orElseThrow(
            () ->
                new HolmgangException(
                    "statefulset " + name + " index " + index + " has no current placement"));
  }
}
