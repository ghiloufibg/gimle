package com.gimle.core.logging;

/**
 * MDC key names used to tag test-scoped log lines with the test that produced them, read back out
 * by {@code logback-test.xml}'s {@code SiftingAppender} (keyed on {@link #TEST_CLASS}) to route
 * each class's output to its own file regardless of execution order under parallel test execution.
 * Set by {@code TestMdcExtension} (test scope, {@code gimle-core}'s own test sources) -- mirrors
 * {@link InstanceMdcKeys}'s production shape, applied to test logs instead of instance logs.
 */
public final class TestMdcKeys {

  public static final String TEST_CLASS = "testClass";
  public static final String TEST_METHOD = "testMethod";

  private TestMdcKeys() {}
}
