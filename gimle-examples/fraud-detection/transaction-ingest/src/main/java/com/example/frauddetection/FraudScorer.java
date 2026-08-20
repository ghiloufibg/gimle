package com.example.frauddetection;

/**
 * The fabric service contract shared by transaction-ingest and fraud-scorer. Each module bundles
 * its own literal copy of this interface (same fully-qualified name, same signature) rather than
 * depending on a shared compile-time API jar -- the fabric's service catalog resolves lookups by
 * interface name and dispatches through a proxy built from the caller's own {@code Class} object,
 * so two independently compiled, structurally identical copies interoperate correctly across the
 * wire. The same "structural contract, not a shared jar" demonstration
 * gimle-examples/greeter-provider and greeter-consumer already establish.
 */
public interface FraudScorer {

  /** Scores {@code transaction}, always returning a result -- never throws for a business
   *  decision, only for a genuine infrastructure failure (see the caller's own retry handling). */
  ScoreResult score(Transaction transaction);
}
