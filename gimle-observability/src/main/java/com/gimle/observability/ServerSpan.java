package com.gimle.observability;

/**
 * One in-flight server-side span, started by {@link GimleTracing#startServerSpan} and ended by
 * {@link #close()} -- shaped for try-with-resources so a request handler cannot leak an unended
 * span down an early-return or exception path.
 *
 * <p>Exists so a process that serves requests can be traced without taking a compile-time
 * dependency on the OpenTelemetry API itself: everything OTel-shaped stays inside this module,
 * behind these two methods. {@link #close()} deliberately narrows {@link AutoCloseable}'s own
 * {@code throws Exception} away -- ending a span is best-effort observability work and must never
 * become a second failure a handler has to deal with.
 */
public interface ServerSpan extends AutoCloseable {

  /** Records the response status the handler produced; call before {@link #close()}. */
  void recordStatus(int httpStatus);

  @Override
  void close();

  /** Used whenever no tracer provider is installed -- records nothing, allocates nothing. */
  ServerSpan NOOP =
      new ServerSpan() {
        @Override
        public void recordStatus(int httpStatus) {}

        @Override
        public void close() {}
      };
}
