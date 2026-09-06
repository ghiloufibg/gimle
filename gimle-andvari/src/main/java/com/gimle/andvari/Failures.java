package com.gimle.andvari;

/**
 * Renders a throwable as the one-line detail a log message interpolates.
 *
 * <p>{@link Throwable#getMessage()} alone is not enough: a whole family of the failures this
 * process actually hits carries no message at all -- {@code java.net.ConnectException} raised by
 * the JDK HTTP client for an unresolvable host, {@code ClosedChannelException}, {@code
 * NullPointerException} -- and interpolating a null message renders the literal text {@code
 * "null"}, which tells an operator nothing about what went wrong. Falling back to the exception's
 * class name, and to the first cause that does carry a message, keeps every failure identifiable.
 */
final class Failures {

  /** Bounds the rendered cause chain so a pathologically deep one can't produce a huge log line. */
  private static final int MAX_CAUSE_DEPTH = 5;

  private Failures() {}

  static String describe(Throwable failure) {
    if (failure == null) {
      return "unknown failure";
    }
    StringBuilder rendered = new StringBuilder();
    Throwable current = failure;
    for (int depth = 0; current != null && depth <= MAX_CAUSE_DEPTH; depth++) {
      if (depth > 0) {
        rendered.append(" caused by ");
      }
      String message = current.getMessage();
      if (message != null && !message.isBlank()) {
        return rendered
            .append(depth == 0 ? "" : current.getClass().getName() + ": ")
            .append(message)
            .toString();
      }
      rendered.append(current.getClass().getName());
      Throwable cause = current.getCause();
      current = cause == current ? null : cause;
    }
    return rendered.toString();
  }
}
