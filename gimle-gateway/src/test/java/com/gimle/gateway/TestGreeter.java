package com.gimle.gateway;

/**
 * A trivial single-method service interface for {@link GatewayDispatcherTest} -- top-level and
 * {@code public}, not a nested/private type: {@code SimpleServiceRegistry#invokeByName} reflects
 * through the interface from a different package ({@code com.gimle.module.lifecycle}), which plain
 * (non-{@code setAccessible}) reflection requires to be public with every enclosing type public too
 * -- a private nested interface fails that with an {@link IllegalAccessException}.
 */
public interface TestGreeter {

  String greet(String name);
}
