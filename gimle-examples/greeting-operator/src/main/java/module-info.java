/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires}: the worker JVM's platform layer is boot-only today (no {@code gimle-api} module exists
 * yet), so a plain {@code requires} would fail {@code Configuration.resolve} outright at deploy
 * time. {@code static} lets resolution succeed with the dependency simply unsatisfied; {@code
 * ModuleLayerFactory} (in {@code gimle-module}) separately grants this module's layer readability
 * to the parent classloader's own unnamed module, which is what actually resolves {@link
 * com.gimle.module.lifecycle.ModuleLifecycleHooks}, the Galdr operator SDK, and friends to the
 * platform's real classes at runtime.
 */
module com.gimle.examples.greeting.operator {
  requires static com.gimle.module;
  requires static org.slf4j;

  exports com.gimle.examples.greeting.operator;
}
