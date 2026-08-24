package com.gimle.cli;

import com.gimle.core.tls.TransportProtocol;
import java.io.PrintStream;

/**
 * Shared heads-up printed by {@code set role}/{@code set account}/{@code set rolebinding} after a
 * successful write: in plaintext mode the control plane has no caller identity at all, so {@code
 * ApiServer.requireAuthorized} short-circuits to always-authorized before any RBAC check runs -- an
 * RBAC object created in this mode has zero enforcement effect. This is documented, intentional
 * platform behavior, not a bug; the note exists only because it's easy to miss.
 */
final class RbacWarnings {

  private RbacWarnings() {}

  static void warnIfPlaintext(PrintStream out) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      out.println(
          "note: RBAC has no effect in plaintext mode (no identity, no enforcement) — see"
              + " gimle-docs/docs/architecture/authn-authz.md");
    }
  }
}
