/**
 * {@code com.gimle.module}/{@code com.gimle.core}/{@code org.slf4j} are declared {@code requires
 * static}, not {@code requires} -- see gimle-examples/greeter-provider's own module-info for why
 * (the worker JVM's platform layer is boot-only today; {@code ModuleLayerFactory} separately
 * grants this module's layer readability to the parent unnamed module at runtime, which is what
 * actually resolves these types). {@code com.gimle.core} is named explicitly, rather than relying
 * on it landing on the classpath transitively through {@code com.gimle.module}: {@code
 * com.gimle.module}'s own module-info declares a plain, non-transitive {@code requires
 * com.gimle.core}, so this module needs its own direct requires edge to read {@code
 * GimleClusterException} -- the same reason gimle-module needed one of its own.
 */
module com.example.mapreduce.coordinator {
  requires static com.gimle.module;
  requires static com.gimle.core;
  requires static org.slf4j;

  exports com.example.mapreduce;
  // WordCountJobHooks: ModuleController instantiates this reflectively from outside the module,
  // the same way it does with every Job-kind module's own JobHooks.
  exports com.example.mapreduce.coordinator;
}
