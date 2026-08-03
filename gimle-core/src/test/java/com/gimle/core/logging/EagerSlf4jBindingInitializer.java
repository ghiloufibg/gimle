package com.gimle.core.logging;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;

/**
 * Forces SLF4J's one-time binding to complete before any test class runs, by touching {@link
 * LoggerFactory} once here -- {@code launcherSessionOpened} runs single-threaded, before test
 * discovery or execution starts. Without this, concurrent test classes (root {@code pom.xml}'s
 * {@code junit.jupiter.execution.parallel.mode.classes.default=concurrent}) can race to trigger
 * SLF4J's binding for the first time simultaneously; SLF4J returns a temporary {@code
 * SubstituteLoggerFactory} to a caller that lands mid-binding, which a caller expecting the real
 * {@code LoggerContext} (e.g. a test's own field initializer casting the result) then fails to
 * cast. Auto-registered via {@code META-INF/services}, packaged into every module with tests
 * through the same {@code gimle-core} test-jar dependency as {@link TestMdcExtension}.
 */
public final class EagerSlf4jBindingInitializer implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    LoggerFactory.getILoggerFactory();
  }
}
