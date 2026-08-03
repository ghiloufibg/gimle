package com.gimle.core.protocol;

import java.util.Optional;

/**
 * The outcome of a {@code /bootstrap/csr} interaction, per {@code
 * claudedocs/tls-transport-security-design.md} §4/§4a/§4b: returned synchronously by {@code POST
 * /bootstrap/csr} for an auto-approved request (node join, rotation), or by {@code GET
 * /bootstrap/csr/{requestId}} while polling an {@link CsrPurpose#OPERATOR_CLIENT} request through
 * to approval. {@code requestId} is present only for the {@link CsrRequestStatus#PENDING} case
 * (what a caller polls with next); {@code certificatePem}/{@code caCertificatePem} are present only
 * once {@link CsrRequestStatus#APPROVED}.
 */
public record CsrResult(
    CsrRequestStatus status,
    Optional<String> requestId,
    Optional<String> certificatePem,
    Optional<String> caCertificatePem) {

  public CsrResult {
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    if (requestId == null || certificatePem == null || caCertificatePem == null) {
      throw new IllegalArgumentException("optional fields must not be null (use Optional.empty())");
    }
  }

  public static CsrResult pending(String requestId) {
    return new CsrResult(
        CsrRequestStatus.PENDING, Optional.of(requestId), Optional.empty(), Optional.empty());
  }

  public static CsrResult approved(String certificatePem, String caCertificatePem) {
    return new CsrResult(
        CsrRequestStatus.APPROVED,
        Optional.empty(),
        Optional.of(certificatePem),
        Optional.of(caCertificatePem));
  }
}
