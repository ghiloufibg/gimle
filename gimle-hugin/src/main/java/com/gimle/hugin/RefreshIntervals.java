package com.gimle.hugin;

import java.time.Duration;

/**
 * How often each screen re-reads, derived once from the one interval an operator sets.
 *
 * <p>They are separate because the reads are not comparable. The cluster view is one pair of
 * requests and is the thing being watched, so it gets the interval as given. The services screen
 * costs one request per Service, and the activity feed re-reads a trail that only grows at the
 * speed people change things -- polling either at a two-second cluster tick spends requests on
 * answers that have not changed. Both therefore sit behind a floor, so turning the cluster tick
 * down speeds them up only as far as is useful, and turning it up does not slow them at all.
 */
public record RefreshIntervals(Duration cluster, Duration services, Duration activity) {

  private static final Duration DERIVED_FLOOR = Duration.ofSeconds(5);

  public RefreshIntervals {
    if (cluster == null || services == null || activity == null) {
      throw new IllegalArgumentException("every interval must be set");
    }
  }

  /** The three intervals implied by the one an operator passed for the cluster view. */
  public static RefreshIntervals from(final Duration cluster) {
    Duration derived = cluster.compareTo(DERIVED_FLOOR) > 0 ? cluster : DERIVED_FLOOR;
    return new RefreshIntervals(cluster, derived, derived);
  }
}
