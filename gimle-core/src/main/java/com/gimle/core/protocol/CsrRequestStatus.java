package com.gimle.core.protocol;

/**
 * The state of a submission to {@code POST /bootstrap/csr}, per {@code
 * claudedocs/tls-transport-security-design.md} §4a. {@link CsrPurpose#NODE_CLIENT} requests and
 * rotation requests never observe {@link #PENDING} -- they're signed synchronously in the same HTTP
 * response. Only an {@link CsrPurpose#OPERATOR_CLIENT} request, which is never auto-approved, sits
 * as {@link #PENDING} until a separate {@code approve} call moves it to {@link #APPROVED}. There is
 * no rejected state -- the design has no reject action, only "sits pending until approved".
 */
public enum CsrRequestStatus {
  PENDING,
  APPROVED
}
