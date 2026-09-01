package com.gimle.pki;

/**
 * Notified once per certificate-rotation check, whatever its outcome. Kept deliberately free of any
 * metrics or storage type so this module stays dependency-free: a process wires its own meter
 * registry and its own durable audit trail to this from the outside, the same way {@code
 * gimle-fabric}'s circuit breaker publishes transitions without knowing what records them.
 */
@FunctionalInterface
public interface CertificateRotationListener {

  CertificateRotationListener NONE = status -> {};

  void onCheck(CertificateRotationStatus status);

  /**
   * Both listeners are notified in order; neither one's failure prevents the other from running.
   */
  default CertificateRotationListener andThen(CertificateRotationListener next) {
    return status -> {
      try {
        onCheck(status);
      } finally {
        next.onCheck(status);
      }
    };
  }
}
