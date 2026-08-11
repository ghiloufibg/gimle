/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires}: the worker JVM's platform layer is boot-only today (no {@code gimle-api} module exists
 * yet), so a plain {@code requires} would fail {@code Configuration.resolve} outright at deploy
 * time. {@code static} lets resolution succeed with the dependency simply unsatisfied; {@code
 * ModuleLayerFactory} (in {@code gimle-module}) separately grants this module's layer readability
 * to the parent classloader's own unnamed module, which is what actually resolves {@link
 * com.gimle.module.lifecycle.ModuleLifecycleHooks} and friends to the platform's real classes at
 * runtime. {@code jdk.httpserver} is a real JDK platform module, always present in the boot module
 * layer every {@code ModuleLayer} is ultimately parented on -- unlike the platform types above, a
 * plain {@code requires} for it resolves with no such workaround needed.
 */
module com.gimle.examples.greeter.loadgen {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires jdk.httpserver;

  exports com.gimle.examples.greeter.loadgen;
  // FabricServer (gimle-fabric) reflectively invokes Greeter methods on a registered instance from
  // outside this module (it runs unnamed, per WorkerMain) -- a public type alone isn't enough for
  // that under JPMS strong encapsulation, its package must be exported too. This module never
  // registers a Greeter itself (it only calls one), but it does look one up by Class<Greeter>,
  // which needs the same export for its own literal copy of the interface to resolve identically
  // to the provider's.
  exports com.gimle.examples.greeter;
}
