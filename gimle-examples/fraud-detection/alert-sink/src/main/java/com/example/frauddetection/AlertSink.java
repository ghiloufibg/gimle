package com.example.frauddetection;

/**
 * The fabric service contract shared by fraud-scorer and alert-sink. Each module bundles its own
 * literal copy of this interface (same fully-qualified name, same signature) rather than
 * depending on a shared compile-time API jar -- the fabric's service catalog resolves lookups by
 * interface name and dispatches through a proxy built from the caller's own {@code Class} object,
 * so two independently compiled, structurally identical copies interoperate correctly across the
 * wire. The same "structural contract, not a shared jar" demonstration
 * gimle-examples/greeter-provider and greeter-consumer already establish.
 */
public interface AlertSink {

  /** Delivers an alert for a HIGH-risk transaction. May throw -- see AlertSinkHooks's own
   *  configurable simulated failure rate, the actual point of this app. */
  void alert(AlertPayload payload);
}
