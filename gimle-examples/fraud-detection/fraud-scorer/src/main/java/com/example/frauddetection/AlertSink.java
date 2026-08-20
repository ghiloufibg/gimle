package com.example.frauddetection;

/**
 * The fabric service contract shared by fraud-scorer and alert-sink. Each module bundles its own
 * literal copy of this interface (same fully-qualified name, same signature) rather than
 * depending on a shared compile-time API jar -- see {@link FraudScorer}'s own javadoc for why.
 */
public interface AlertSink {

  /** Delivers an alert for a HIGH-risk transaction. May throw for a genuine simulated delivery
   *  failure (see alert-sink's own AlertSinkHooks) -- that's the point of this app, not a bug to
   *  guard against here: see the caller's own retry-and-move-on handling. */
  void alert(AlertPayload payload);
}
