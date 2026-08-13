package com.gimle.core.protocol;

import java.util.Optional;

/**
 * The {@code POST /bootstrap/csr} request body: a PEM-encoded PKCS#10 CSR, what it's for, and --
 * only for a {@link CsrPurpose#NODE_CLIENT} node-join request -- the one-time bootstrap token
 * authorizing it. Absent for an {@link CsrPurpose#OPERATOR_CLIENT} request (gated by human approval
 * instead, never a token) and for a rotation request (authenticated by the caller's own still-valid
 * mTLS certificate instead, presented at the transport layer rather than in this body).
 */
public record CsrSubmission(CsrPurpose purpose, String csrPem, Optional<String> bootstrapToken) {

  public CsrSubmission {
    if (purpose == null) {
      throw new IllegalArgumentException("purpose must not be null");
    }
    if (csrPem == null || csrPem.isBlank()) {
      throw new IllegalArgumentException("csrPem must not be blank");
    }
    if (bootstrapToken == null) {
      throw new IllegalArgumentException("bootstrapToken must not be null (use Optional.empty())");
    }
  }

  public CsrSubmission(CsrPurpose purpose, String csrPem) {
    this(purpose, csrPem, Optional.empty());
  }
}
