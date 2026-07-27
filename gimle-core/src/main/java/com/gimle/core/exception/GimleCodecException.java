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
}
