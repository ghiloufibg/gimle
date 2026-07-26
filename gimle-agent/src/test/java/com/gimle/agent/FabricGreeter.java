package com.gimle.agent;

/** A trivial cross-process service interface for {@link FabricCrossProcessIntegrationTest}. */
public interface FabricGreeter {

  String greet(String name);
}
