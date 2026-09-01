package com.gimle.pki;

/**
 * What one certificate-rotation check actually did. Only {@link #FAILED} is alert-worthy on its
 * own; the other three are the ordinary steady states a healthy process cycles through.
 */
public enum CertificateRotationOutcome {

  /** The transport is plaintext, so there is no leaf certificate to keep fresh at all. */
  DISABLED,

  /** A certificate was read and is not yet inside its renewal window. */
  NOT_DUE,

  /** A renewal was due, was requested, and the fresh certificate is now on disk. */
  ROTATED,

  /**
   * The check itself failed -- the certificate could not be read, no CSR endpoint was configured
   * for a certificate that is already due, or the rotation request was rejected or never completed.
   * The previous certificate (if any) is untouched and still whatever validity it had.
   */
  FAILED
}
