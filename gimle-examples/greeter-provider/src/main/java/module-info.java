/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires}: the worker JVM's platform layer is boot-only today (no {@code gimle-api} module exists
 * yet), so a plain {@code requires} would fail {@code Configuration.resolve} outright at deploy
 * time. {@code static} lets resolution succeed with the dependency simply unsatisfied; {@code
 * ModuleLayerFactory} (in {@code gimle-module}) separately grants this module's layer readability
 * to the parent classloader's own unnamed module, which is what actually resolves {@link
 * com.gimle.module.lifecycle.ModuleLifecycleHooks} and friends to the platform's real classes at
 * runtime.
 */
module com.gimle.examples.greeter.provider {
  requires static com.gimle.module;
  requires static org.slf4j;
  // com.sun.net.httpserver.HttpServer serves this module's own status endpoint -- the real,
  // genuinely-listening port GreeterProviderHooks reports via ctx.reportPort.
  requires jdk.httpserver;

  exports com.gimle.examples.greeter.provider;
  // FabricServer (gimle-fabric) reflectively invokes Greeter methods on the registered instance
  // from outside this module (it runs unnamed, per WorkerMain) -- a public type alone isn't
  // enough for that under JPMS strong encapsulation, its package must be exported too.
  exports com.gimle.examples.greeter;
}
