/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires} -- see gimle-examples/greeter-provider's own module-info for why (the worker JVM's
 * platform layer is boot-only today; {@code ModuleLayerFactory} separately grants this module's
 * layer readability to the parent unnamed module at runtime, which is what actually resolves
 * these types).
 */
module com.example.frauddetection.ingest {
  requires static com.gimle.module;
  requires static org.slf4j;

  // This module never registers a FraudScorer itself (it only calls one), but it does look one
  // up by Class<FraudScorer>, which needs the same export for its own literal copies of the
  // interface and its record types to resolve identically to fraud-scorer's.
  exports com.example.frauddetection;
  // TransactionIngestHooks: ModuleController instantiates this reflectively from outside the
  // module, the same way it does with every long-running module's own ModuleLifecycleHooks.
  exports com.example.frauddetection.ingest;
}
