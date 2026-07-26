package com.gimle.core.exception;

/**
 * A {@code gimle-module.yaml} descriptor (or the artifact carrying it) is malformed: missing field,
 * unparseable version/range, invalid isolation tier, request exceeding limit, an automatic-module
 * JAR, or a duplicate artifact under the same {@code ModuleId} with different content.
 */
public class GimleManifestException extends RuntimeException {

  public GimleManifestException(String message) {
    super(message);
  }

  public GimleManifestException(String message, Throwable cause) {
    super(message, cause);
  }
}
