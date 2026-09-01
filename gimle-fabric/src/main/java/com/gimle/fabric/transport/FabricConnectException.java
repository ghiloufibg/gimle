package com.gimle.fabric.transport;

import java.io.IOException;

/**
 * A fabric call that failed while the connection was still being established -- no request byte
 * ever reached the target, so the call provably did not execute.
 *
 * <p>That distinction is the whole reason this type exists separately from the plain {@link
 * IOException} every other transport failure surfaces as: a caller can retry a connect failure
 * against a different endpoint whatever the invoked method does, because there is no partially
 * applied effect anywhere to duplicate. Once the request has been written, the same {@code
 * IOException} could equally mean "the target never saw it" or "the target ran it and the answer
 * was lost", and only a method that declares itself idempotent may be retried on those terms.
 *
 * <p>A call that exceeds its own overall deadline is deliberately <em>not</em> reported as a
 * connect failure even when the deadline happened to elapse during connect: one timeout bounds
 * connect, write, and read together, so which phase it interrupted isn't knowable from here, and
 * the safe reading of an unknown phase is "the request may have been sent".
 */
public final class FabricConnectException extends IOException {

  private static final long serialVersionUID = 1L;

  public FabricConnectException(String message, Throwable cause) {
    super(message, cause);
  }
}
