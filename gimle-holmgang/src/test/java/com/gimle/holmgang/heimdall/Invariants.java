package com.gimle.holmgang.heimdall;

/** Factories for the {@link Invariant}s scenarios hold over a scoped window. */
public final class Invariants {

  private Invariants() {}

  public static DeploymentInvariants deployment(final String name) {
    return new DeploymentInvariants(name);
  }

  public static final class DeploymentInvariants {

    private final String name;

    private DeploymentInvariants(final String name) {
      this.name = name;
    }

    /**
     * The deployment keeps at least {@code count} {@code ACTIVE} instances on every observed view
     * -- the guard behind every "stays serving throughout the rollout" assertion. A view where the
     * deployment is missing entirely is a violation, not a pass: a deployment that vanished
     * mid-window kept zero replicas active.
     */
    public Invariant minActiveReplicas(final int count) {
      return new Invariant(
          "deployment " + name + " keeps >= " + count + " ACTIVE replicas",
          view ->
              view.deployments().containsKey(name)
                  && view.deployments().get(name).countInState("ACTIVE") >= count);
    }

    /**
     * The deployment never gets an instance placed -- what a submission blocked by scheduling
     * (every node cordoned, no feasible placement) must keep looking like for the whole window.
     */
    public Invariant staysUnplaced() {
      return new Invariant(
          "deployment " + name + " stays unplaced",
          view ->
              !view.deployments().containsKey(name)
                  || view.deployments().get(name).instances().isEmpty());
    }
  }
}
