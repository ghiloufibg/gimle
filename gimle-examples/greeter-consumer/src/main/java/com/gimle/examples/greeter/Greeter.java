package com.gimle.examples.greeter;

/**
 * greeter-consumer's own copy of greeter-provider's {@code Greeter} interface -- same
 * fully-qualified name and signature, deliberately duplicated rather than shared via a compile-time
 * API jar. See greeter-provider's copy of this file for why that's safe: the fabric's service
 * catalog resolves by interface name, not by {@code Class} identity.
 */
public interface Greeter {

  String greet(String name);
}
