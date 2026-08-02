package com.gimle.examples.greeter;

/**
 * The fabric service contract shared by {@code greeter-provider} and {@code greeter-consumer}. Each
 * module bundles its own literal copy of this interface (same fully-qualified name, same signature)
 * rather than depending on a shared compile-time API jar -- the fabric's service catalog resolves
 * lookups by interface name and dispatches through a proxy built from the caller's own {@code
 * Class} object (see {@code FabricServiceRegistry}), so two independently compiled, structurally
 * identical copies interoperate correctly across the wire. That's a deliberate demonstration of
 * this platform's actual value proposition: modules agree on a structural contract, not a shared
 * jar.
 */
public interface Greeter {

  String greet(String name);
}
