package com.gimle.hilmir.extension;

import com.gimle.hilmir.HilmirException;

/**
 * Rewrites a control plane's opaque 401/403 into a specific, actionable message for the gateway
 * extension: {@code gimle-system} is a reserved tenant that only an mTLS caller in the {@code
 * gimle:operators} group may write to (see {@code ApiServer.rejectIfReservedSystemTenant}), and
 * Andvari's own artifact-push authorization re-checks the same group -- so an auth failure here is,
 * in practice, almost always a caller not presenting operator PKI material, not some other cause.
 *
 * <p>{@code com.gimle.hilmir.release.ControlPlaneApi.expectSuccess} has no status-code-specific
 * handling for 401/403 (it falls through to a generic "unexpected response (N): body" message), so
 * this inspects that generic message rather than a typed status field -- there is no public, typed
 * way from outside {@code com.gimle.hilmir.release} to ask an {@code HilmirException} what HTTP
 * status produced it.
 */
final class AuthErrors {

  private AuthErrors() {}

  static HilmirException translate(HilmirException e) {
    String message = e.getMessage();
    if (message != null && (message.contains("(401)") || message.contains("(403)"))) {
      return new HilmirException(
          "the control plane rejected this request as unauthorized -- hilmir enable/disable"
              + " gateway targets the reserved gimle-system tenant, which requires operator"
              + " (gimle:operators-group) mTLS credentials (-Dgimle.tls.certFile/keyFile/caFile"
              + " pointing at an operator leaf); original error: "
              + message,
          e);
    }
    return e;
  }
}
