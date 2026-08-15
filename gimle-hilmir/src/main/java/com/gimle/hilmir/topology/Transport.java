package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.Locale;

/** Cluster-wide transport security mode, matching {@code gimle.transport.protocol}'s two modes. */
public enum Transport {
  PLAINTEXT,
  MTLS;

  static Transport fromField(final String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "plaintext" -> PLAINTEXT;
      case "mtls" -> MTLS;
      default ->
          throw new GimleManifestException(
              "unknown transport: " + value + " (expected plaintext or mtls)");
    };
  }
}
