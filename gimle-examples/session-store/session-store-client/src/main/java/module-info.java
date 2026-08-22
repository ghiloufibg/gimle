/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires} -- see gimle-examples/greeter-provider's own module-info for why (the worker JVM's
 * platform layer is boot-only today; {@code ModuleLayerFactory} separately grants this module's
 * layer readability to the parent unnamed module at runtime, which is what actually resolves
 * these types).
 */
module com.example.sessionstore.client {
  requires static com.gimle.module;
  requires static org.slf4j;

  // This module never registers a SessionStore itself (it only calls one), but it does look one
  // up by Class<SessionStore>, which needs the same export for its own literal copy of the
  // interface to resolve identically to the store's.
  exports com.example.sessionstore;
  // SessionStoreClientHooks: ModuleController instantiates this reflectively from outside the
  // module, the same way it does with every long-running module's own ModuleLifecycleHooks.
  exports com.example.sessionstore.client;
}
