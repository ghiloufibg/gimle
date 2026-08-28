package com.gimle.core.saga;

import com.gimle.core.hash.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A stable, short hash identifying a failure's <em>shape</em> -- exception type, normalized
 * message, and the topmost stack frame -- so the same underlying defect observed across different
 * tests, runs, and days clusters under one key even when the raw message differs per occurrence.
 *
 * <p>Normalization exists because real failure messages embed run-specific noise that would
 * otherwise make every occurrence unique: port numbers, durations, pids, hex identifiers,
 * timestamps. Digit runs and long hex runs are replaced with a placeholder before hashing; anything
 * subtler stays significant on purpose -- over-normalizing would merge genuinely distinct failures,
 * which is worse for triage than occasionally splitting one defect across two signatures.
 */
public final class FailureSignature {

  private static final int SIGNATURE_HEX_LENGTH = 16;
  private static final int MAX_MESSAGE_LENGTH = 200;

  private FailureSignature() {}

  /**
   * Computes the signature. {@code message} and {@code topFrame} may be null (some throwables have
   * no message; a frame may be unavailable for synthetic failures); the exception type alone still
   * yields a stable signature.
   */
  public static String of(String exceptionType, String message, String topFrame) {
    String normalizedMessage = normalize(message == null ? "" : message);
    String frame = topFrame == null ? "" : topFrame;
    String input = exceptionType + "\n" + normalizedMessage + "\n" + frame;
    return HexFormat.of()
        .formatHex(Sha256.sha256Digest().digest(input.getBytes(StandardCharsets.UTF_8)))
        .substring(0, SIGNATURE_HEX_LENGTH);
  }

  private static String normalize(String message) {
    String truncated =
        message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
    return truncated
        .replaceAll("[0-9a-fA-F]{8,}", "#")
        .replaceAll("[0-9]+", "#")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
