package com.gimle.core.protocol;

/**
 * The state of a submission to {@code POST /bootstrap/csr}. {@link CsrPurpose#NODE_CLIENT} requests
 * and rotation requests never observe {@link #PENDING} -- they're signed synchronously in the same
 * HTTP response. Only an {@link CsrPurpose#OPERATOR_CLIENT} request, which is never auto-approved,
 * sits as {@link #PENDING} until a separate {@code approve} call moves it to {@link #APPROVED}.
 * There is no rejected state: a request either sits pending or gets approved, never explicitly
 * denied.
 */
public enum CsrRequestStatus {
  PENDING,
  APPROVED
}
