/**
 * {@code com.gimle.module}/{@code com.gimle.core}/{@code org.slf4j} are declared {@code requires
 * static}, not {@code requires}: the worker JVM's platform layer is boot-only today (no {@code
 * gimle-api} module exists yet), so a plain {@code requires} would fail {@code
 * Configuration.resolve} outright at deploy time. {@code static} lets resolution succeed with the
 * dependency simply unsatisfied; {@code ModuleLayerFactory} (in {@code gimle-module}) separately
 * grants this module's layer readability to the parent classloader's own unnamed module, which is
 * what actually resolves {@link com.gimle.module.lifecycle.ModuleLifecycleHooks}/friends -- and,
 * since {@code gimle-worker} launches every gimle-* jar unnamed itself, {@link
 * com.gimle.core.protocol.Json} too -- to the platform's real classes at runtime. {@code
 * jdk.httpserver}/{@code java.net.http} are real JDK platform modules, always present in the boot
 * module layer every {@code ModuleLayer} is ultimately parented on -- unlike the platform types
 * above, a plain {@code requires} for them resolves with no such workaround needed.
 */
module com.gimle.gateway {
  requires static com.gimle.module;
  requires static com.gimle.core;
  requires static org.slf4j;
  requires jdk.httpserver;
  requires java.net.http;

  exports com.gimle.gateway;
}
