/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires} -- see gimle-examples/greeter-provider's own module-info for why (the worker JVM's
 * platform layer is boot-only today; {@code ModuleLayerFactory} separately grants this module's
 * layer readability to the parent unnamed module at runtime, which is what actually resolves
 * these types).
 */
module com.example.sessionstore.store {
  requires static com.gimle.module;
  requires static org.slf4j;

  // FabricServer (gimle-fabric) reflectively invokes SessionStore methods on the registered
  // instance from outside this module -- a public type alone isn't enough for that under JPMS
  // strong encapsulation, its package must be exported too.
  exports com.example.sessionstore;
  // SessionStoreHooks: ModuleController instantiates this reflectively from outside the module,
  // the same way it does with every long-running module's own ModuleLifecycleHooks.
  exports com.example.sessionstore.store;
}
