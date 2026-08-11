package com.gimle.examples.greeter;

/**
 * The fabric service contract shared by {@code greeter-provider}/{@code greeter-consumer}/{@code
 * greeter-load-generator}. Each module bundles its own literal copy of this interface (same
 * fully-qualified name, same signature) rather than depending on a shared compile-time API jar --
 * the fabric's service catalog resolves lookups by interface name and dispatches through a proxy
 * built from the caller's own {@code Class} object (see {@code FabricServiceRegistry}), so
 * independently compiled, structurally identical copies interoperate correctly across the wire.
 */
public interface Greeter {

  String greet(String name);
}
