package com.gimle.holmgang.heimdall;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/** Conditions over one deployment's control-plane-visible state. */
public final class DeploymentConditions {

  private final Heimdall heimdall;
  private final OptionalInt replicaFilter;
  private final String name;

  DeploymentConditions(
      final Heimdall heimdall, final OptionalInt replicaFilter, final String name) {
    this.heimdall = heimdall;
    this.replicaFilter = replicaFilter;
    this.name = name;
  }

  /** Every placed instance reports {@code ACTIVE}, and at least one instance is placed. */
  public HolmgangCondition isActive() {
    return register(
        "deployment " + name + " is ACTIVE",
        view -> view.deployments().containsKey(name) && view.deployments().get(name).allActive());
  }

  public HolmgangCondition hasActiveReplicas(final int count) {
    return register(
        "deployment " + name + " has " + count + " ACTIVE replicas",
        view ->
            view.deployments().containsKey(name)
                && view.deployments().get(name).countInState("ACTIVE") == count);
  }

  public HolmgangCondition allOnVersion(final String version, final int count) {
    return register(
        "all " + count + " instances of " + name + " are ACTIVE on version " + version,
        view ->
            view.deployments().containsKey(name)
                && view.deployments().get(name).allActiveOnVersion(version, count));
  }

  /**
   * At least one instance reports {@code FAILED} -- a terminal escalation with no path back to
   * {@code ACTIVE} without a fresh deploy, so one instance reaching it is the whole answer.
   */
  public HolmgangCondition hasFailedInstance() {
    return register(
        "deployment " + name + " has a FAILED instance",
        view ->
            view.deployments().containsKey(name)
                && view.deployments().get(name).countInState("FAILED") > 0);
  }

  public HolmgangCondition isAbsent() {
    return register(
        "deployment " + name + " is absent", view -> !view.deployments().containsKey(name));
  }

  public HolmgangCondition reportsQuotaViolation() {
    return register(
        "deployment " + name + " reports a quota violation",
        view ->
            view.deployments().containsKey(name) && view.deployments().get(name).quotaViolating());
  }

  private HolmgangCondition register(
      final String description, final Predicate<ClusterView> predicate) {
    final String scoped =
        description
            + (replicaFilter.isPresent()
                ? " (observed via control-plane replica #" + replicaFilter.getAsInt() + ")"
                : "");
    return heimdall.registerViewCondition(scoped, replicaFilter, Optional.of(name), predicate);
  }
}
