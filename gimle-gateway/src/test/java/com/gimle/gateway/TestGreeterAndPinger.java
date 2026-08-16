package com.gimle.gateway;

/** Like {@link TestGreeter}, plus a {@code void} method -- for the tests that need one. */
public interface TestGreeterAndPinger {

  String greet(String name);

  void ping();
}
