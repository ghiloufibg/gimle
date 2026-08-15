package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;

/** The single shared port-range check every port-carrying record in this package applies. */
final class Ports {

  private Ports() {}

  static void requireValid(final int port, final String field) {
    if (port < 1 || port > 65535) {
      throw new GimleManifestException(field + " must be between 1 and 65535, got " + port);
    }
  }
}
