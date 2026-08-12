package com.gimle.core.time;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Supplies a fresh {@link TestClock} to any test method (or {@code @BeforeEach}, or constructor)
 * that declares one as a parameter:
 *
 * <pre>{@code
 * @Test
 * void an_open_breaker_half_opens_once_its_cooldown_elapses(TestClock clock) {
 *   CircuitBreaker breaker = new CircuitBreaker(2, 0.5, Duration.ofSeconds(5), clock);
 *   ...
 *   clock.advance(Duration.ofSeconds(5));
 *   assertEquals(State.HALF_OPEN, breaker.state());
 * }
 * }</pre>
 *
 * <p>Auto-registered repo-wide through {@code META-INF/services} plus Surefire's {@code
 * junit.jupiter.extensions.autodetection.enabled=true} (root {@code pom.xml}), the same mechanism
 * {@code TestMdcExtension} already uses -- no {@code @ExtendWith} on any test class. Registering a
 * {@link ParameterResolver} globally is safe precisely because it resolves nothing but its own
 * parameter type: a test declaring no {@link TestClock} parameter is unaffected.
 *
 * <p>One instance per test method, stored on that method's own {@link ExtensionContext}, so several
 * parameters (or a {@code @BeforeEach} and the test body) see the same clock rather than diverging
 * timelines, and nothing leaks between tests.
 */
public final class TestClockExtension implements ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(TestClockExtension.class);

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
    return parameterContext.getParameter().getType() == TestClock.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    return context.getStore(NAMESPACE).getOrComputeIfAbsent(TestClock.class, k -> new TestClock());
  }
}
