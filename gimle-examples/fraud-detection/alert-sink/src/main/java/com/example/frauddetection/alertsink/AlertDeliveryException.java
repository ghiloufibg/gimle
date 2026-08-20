package com.example.frauddetection.alertsink;

/** Simulates a downstream alert-delivery failure (a webhook timeout, a rejected notification). */
public final class AlertDeliveryException extends RuntimeException {

  public AlertDeliveryException(String message) {
    super(message);
  }
}
