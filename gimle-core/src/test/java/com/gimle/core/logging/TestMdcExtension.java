package com.gimle.core.logging;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.MDC;

/**
 * Tags every log line emitted during a test with {@link TestMdcKeys#TEST_CLASS}/{@link
 * TestMdcKeys#TEST_METHOD}, auto-registered repo-wide via {@code META-INF/services} and Surefire's
 * {@code junit.jupiter.extensions.autodetection.enabled=true} (root {@code pom.xml}) rather than
 * requiring {@code @ExtendWith} on every test class. {@code logback-test.xml}'s {@code
 * SiftingAppender} reads {@link TestMdcKeys#TEST_CLASS} back out to route each class's output to
 * its own file under {@code target/test-logs/}, independent of what else is running concurrently in
 * the same fork.
 */
public final class TestMdcExtension implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    MDC.put(TestMdcKeys.TEST_CLASS, context.getRequiredTestClass().getSimpleName());
    context.getTestMethod().ifPresent(method -> MDC.put(TestMdcKeys.TEST_METHOD, method.getName()));
  }

  @Override
  public void afterEach(ExtensionContext context) {
    MDC.remove(TestMdcKeys.TEST_CLASS);
    MDC.remove(TestMdcKeys.TEST_METHOD);
  }
}
