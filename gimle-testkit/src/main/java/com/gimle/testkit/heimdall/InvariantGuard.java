package com.gimle.testkit.heimdall;

import java.util.Optional;

/**
 * Holds an {@link Invariant} over every {@link ClusterView} observed between creation and {@link
 * #close()}. The first violating view is captured with its own forensic report at the moment of
 * violation -- not reconstructed later -- and {@code close()} throws it, so a try-with-resources
 * block around a scenario step reads as "this must hold throughout".
 */
public final class InvariantGuard implements AutoCloseable {

  private final Heimdall heimdall;
  private final Invariant invariant;
  private volatile HeimdallConditionError violation;

  InvariantGuard(final Heimdall heimdall, final Invariant invariant) {
    this.heimdall = heimdall;
    this.invariant = invariant;
  }

  void evaluate(final ClusterView view) {
    if (violation != null) {
      return;
    }
    if (!invariant.holds().test(view)) {
      violation =
          heimdall.conditionError(
              "invariant violated: " + invariant.description(), Optional.empty());
    }
  }

  public boolean violated() {
    return violation != null;
  }

  @Override
  public void close() {
    heimdall.unregister(this);
    if (violation != null) {
      throw violation;
    }
  }
}
