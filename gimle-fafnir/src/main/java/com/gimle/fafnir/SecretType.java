package com.gimle.fafnir;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shape a secret's plaintext is declared to have, checked once at write time. Deliberately a
 * very small set: {@link #OPAQUE} (the default -- store anything, check nothing) plus the two PEM
 * shapes the platform itself later mounts as files for a workload's TLS material. A larger taxonomy
 * would mostly be guesswork about value shapes nothing here ever interprets, and every type that
 * exists has to keep earning its validator.
 *
 * <p>The point is where the failure surfaces: with no declared type, a truncated certificate or a
 * wrongly-encoded key is accepted, encrypted, and replicated exactly like a good one, and only
 * fails much later when a module launches and something tries to parse it. Declaring a type moves
 * that failure back to the {@code secret set} call that caused it.
 */
public enum SecretType {
  /** Any byte sequence, including an empty one -- the default, and the only unvalidated type. */
  OPAQUE(null, ""),
  PEM_CERTIFICATE(Pattern.compile("-----BEGIN (CERTIFICATE)-----"), "-----BEGIN CERTIFICATE-----"),
  // Covers PKCS#8 ("PRIVATE KEY") plus the PKCS#1/SEC1/encrypted spellings openssl still emits
  // ("RSA PRIVATE KEY", "EC PRIVATE KEY", "ENCRYPTED PRIVATE KEY") -- rejecting a perfectly valid
  // key purely for its label would make this type useless for real material.
  PEM_PRIVATE_KEY(
      Pattern.compile("-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----"),
      "-----BEGIN [RSA|EC|ENCRYPTED ]PRIVATE KEY-----");

  private final Pattern beginMarker;
  private final String expectedMarker;

  SecretType(Pattern beginMarker, String expectedMarker) {
    this.beginMarker = beginMarker;
    this.expectedMarker = expectedMarker;
  }

  /**
   * Validates {@code plaintext} against this type, throwing {@link GimleSecretsException} naming
   * what was expected if it doesn't hold. A no-op for {@link #OPAQUE}.
   */
  public void validate(String tenantId, String key, byte[] plaintext) {
    if (beginMarker == null) {
      return;
    }
    String problem = pemProblem(new String(plaintext, StandardCharsets.UTF_8));
    if (problem != null) {
      throw GimleSecretsException.malformedSecretValue(tenantId, key, wireName(), problem);
    }
  }

  /** This type's CLI/wire spelling: lowercase, hyphen-separated ({@code pem-certificate}). */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * Parses a wire/CLI spelling -- {@code pem-certificate} and {@code PEM_CERTIFICATE} both name the
   * same type, so an operator never has to remember which punctuation this enum happens to use. A
   * blank or absent value means {@link #OPAQUE}, which is what keeps declaring a type opt-in.
   */
  public static SecretType fromWire(String raw) {
    if (raw == null || raw.isBlank()) {
      return OPAQUE;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    for (SecretType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    throw new IllegalArgumentException(
        "unknown secret type '" + raw + "'; expected one of " + wireNames());
  }

  public static String wireNames() {
    StringBuilder names = new StringBuilder();
    for (SecretType type : values()) {
      if (!names.isEmpty()) {
        names.append(", ");
      }
      names.append(type.wireName());
    }
    return names.toString();
  }

  /**
   * A structural check only -- the PEM framing is present, opened and closed with the same label,
   * with a base64-shaped body between the markers. Deliberately not a parse into an {@code
   * X509Certificate}/{@code PrivateKey}: the point is catching the truncation and encoding mistakes
   * that otherwise surface at module launch, not standing in for the JDK's own parser at write
   * time, and private key material is never decoded here at all -- that would mean reconstructing
   * the key in this process for no benefit. Returns {@code null} when the value is well-formed.
   */
  private String pemProblem(String text) {
    Matcher matcher = beginMarker.matcher(text);
    if (!matcher.find()) {
      return "missing a '" + expectedMarker + "' marker";
    }
    String label = matcher.group(1);
    String end = "-----END " + label + "-----";
    int endAt = text.indexOf(end, matcher.end());
    if (endAt < 0) {
      return "missing '" + end + "' after the opening marker";
    }
    String body = text.substring(matcher.end(), endAt).strip();
    if (body.isEmpty()) {
      return "no base64 body between the PEM markers";
    }
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (!isBase64Char(c) && !Character.isWhitespace(c)) {
        return "non-base64 character '" + c + "' in the PEM body";
      }
    }
    return null;
  }

  private static boolean isBase64Char(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '+'
        || c == '/'
        || c == '=';
  }
}
