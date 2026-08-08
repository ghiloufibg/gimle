package com.gimle.core.exception;

/**
 * A length-prefixed wire frame (Raft peer traffic, fabric same-machine/cross-machine transport)
 * declared a length that cannot be trusted before allocating a buffer for it: negative, or larger
 * than any legitimate frame this codec ever produces. Thrown by the reader before the allocation it
 * would otherwise justify, so a corrupted peer or a single bit-flipped length prefix fails fast
 * with a clear cause instead of an {@code OutOfMemoryError} or a raw {@code
 * NegativeArraySizeException}.
 */
public class GimleCodecException extends RuntimeException {

  private GimleCodecException(String message) {
    super(message);
  }

  public static GimleCodecException invalidFrameLength(int declaredLength) {
    return new GimleCodecException("negative frame length in wire protocol: " + declaredLength);
  }

  public static GimleCodecException frameTooLarge(int declaredLength, int maxLength) {
    return new GimleCodecException(
        "wire frame length "
            + declaredLength
            + " exceeds the maximum allowed "
            + maxLength
            + " bytes; rejecting before allocation");
  }

  public static GimleCodecException unsupportedVersion(
      int declaredVersion, int maxSupportedVersion) {
    return new GimleCodecException(
        "wire protocol version "
            + declaredVersion
            + " is not supported by this reader (max supported: "
            + maxSupportedVersion
            + ")");
  }

  /**
   * Rejects a network-supplied length or element count before it is used to size an allocation --
   * negative outright, or above {@code maxLength} for this field. Shared by every codec that reads
   * a length/count prefix ahead of a {@code byte[]}/collection allocation (Raft, fabric, SWIM
   * gossip, service catalog), so the bound-check logic exists in exactly one place.
   */
  public static void checkFrameLength(int declaredLength, int maxLength) {
    if (declaredLength < 0) {
      throw invalidFrameLength(declaredLength);
    }
    if (declaredLength > maxLength) {
      throw frameTooLarge(declaredLength, maxLength);
    }
  }

  /**
   * Rejects a frame whose wire-protocol version this reader doesn't understand -- checked before
   * any version-specific field is decoded, so an unsupported version fails fast with a clear cause
   * rather than misdecoding fields a newer/older writer laid out differently. No negotiation: a
   * reader either understands {@code declaredVersion} or it doesn't.
   */
  public static void checkVersion(int declaredVersion, int maxSupportedVersion) {
    if (declaredVersion < 1 || declaredVersion > maxSupportedVersion) {
      throw unsupportedVersion(declaredVersion, maxSupportedVersion);
    }
  }
}
