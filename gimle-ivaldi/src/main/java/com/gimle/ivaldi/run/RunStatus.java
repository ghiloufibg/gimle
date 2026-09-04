package com.gimle.ivaldi.run;

/**
 * A run's state, matching the console's own {@code RunStatus} vocabulary field for field so no
 * mapping step is ever needed between the two: {@code idle} (nothing has run against this
 * controller yet, or the last run was torn down cleanly), {@code validating} (tier-2 validation of
 * the submitted files), {@code booting} (the topology changed or this is the cluster's first run,
 * so {@code MachineLauncher.up} is rebuilding the process tree -- skipped entirely when the
 * topology is unchanged), {@code seeding} (pushing jar-sourced workload artifacts to the cluster's
 * Andvari through its control plane), {@code deploying} (rendering and applying {@code
 * bundle.yaml}), {@code running} (deployed successfully), {@code stopping} ({@code
 * MachineLauncher.down} in progress), {@code failed} (any step above raised; see the run's own
 * {@code error}).
 */
public enum RunStatus {
  IDLE,
  VALIDATING,
  BOOTING,
  SEEDING,
  DEPLOYING,
  RUNNING,
  STOPPING,
  FAILED;

  /** A run in one of these states is still doing something; a second run must wait for it. */
  public boolean isInFlight() {
    return this == VALIDATING
        || this == BOOTING
        || this == SEEDING
        || this == DEPLOYING
        || this == STOPPING;
  }

  public String wireValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
