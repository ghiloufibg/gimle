package com.gimle.gateway;

/**
 * A single-{@code Integer}-argument service interface for {@link GatewayDispatcherTest}'s own
 * {@code INT}-{@code ParamType} coverage.
 */
public interface TestAdder {

  Integer increment(Integer value);
}
