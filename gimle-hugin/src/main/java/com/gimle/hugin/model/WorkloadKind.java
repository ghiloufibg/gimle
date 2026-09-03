package com.gimle.hugin.model;

/**
 * Which kind of workload an instance belongs to. The platform itself keys an instance on the
 * tenant-scoped {@code (name, index)} pair regardless of kind -- so this is what a row is labelled
 * with, never part of its identity -- but an operator reading a mixed table still needs to know
 * whether the row in front of them is a replica of something scaled, or one of a set pinned to a
 * node.
 */
public enum WorkloadKind {
  DEPLOYMENT("/deployments", "DEPLOY"),
  DAEMON_SET("/daemonsets", "DAEMON"),
  STATEFUL_SET("/statefulsets", "STATEFUL");

  private final String route;
  private final String label;

  WorkloadKind(final String route, final String label) {
    this.route = route;
    this.label = label;
  }

  /** The list route this kind's workloads are read from. */
  public String route() {
    return route;
  }

  /** The word the table's KIND column shows, short enough to hold a column at 80 columns. */
  public String label() {
    return label;
  }
}
