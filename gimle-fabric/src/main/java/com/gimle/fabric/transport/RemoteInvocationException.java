package com.gimle.fabric.transport;

import java.util.Optional;

/**
 * The service on the far end of a fabric call threw, and its own exception object could not be
 * rebuilt in this caller: either the target's exception type is not loadable here -- a hosted
 * module routinely defines its service contract's exception types inside its own {@code
 * ModuleLayer}, and the calling module has no copy of them -- or the exception could not be
 * serialized at all.
 *
 * <p>Carries the remote type's binary name and its message verbatim, because those are exactly what
 * a caller needs to tell the two outcomes apart that matter: the target rejected the call on its
 * own terms, or the wire broke. Collapsing the first into a decode failure makes it read as the
 * second, which is the one thing a caller must never have to guess at.
 *
 * <p>Never raised for a target exception that did cross intact -- that one is rethrown as itself,
 * so a caller that can see the type keeps catching it by type.
 */
public final class RemoteInvocationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String remoteTypeName;

  /** Null exactly when the remote exception's own {@code getMessage()} was null. */
  private final String remoteMessage;

  public RemoteInvocationException(String remoteTypeName, Optional<String> remoteMessage) {
    super(describe(remoteTypeName, remoteMessage));
    this.remoteTypeName = remoteTypeName;
    this.remoteMessage = remoteMessage.orElse(null);
  }

  /** The binary name of the exception class the target actually threw. */
  public String remoteTypeName() {
    return remoteTypeName;
  }

  /** The target exception's own message, empty when it had none. */
  public Optional<String> remoteMessage() {
    return Optional.ofNullable(remoteMessage);
  }

  private static String describe(String remoteTypeName, Optional<String> remoteMessage) {
    return "the fabric call target threw "
        + remoteTypeName
        + remoteMessage.map(message -> ": " + message).orElse(" (no message)")
        + " -- reported by name because that exception could not be reconstructed in this caller";
  }
}
