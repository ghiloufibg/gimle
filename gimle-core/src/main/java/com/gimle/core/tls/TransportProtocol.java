package com.gimle.core.tls;

/**
 * The cluster-wide {@code gimle.transport.protocol} setting: a single switch for every
 * network-exposed transport (API server, Raft peer RPC, gossip, fabric cross-machine), not a
 * per-component choice -- a cluster is either secured or it isn't.
 */
public enum TransportProtocol {
  PLAINTEXT,
  TLS;

  private static final String PROPERTY = "gimle.transport.protocol";
  private static final String ENV_VAR = "GIMLE_TRANSPORT_PROTOCOL";

  /**
   * Reads the configured protocol once at process startup: the {@code -D} system property first,
   * then the {@code GIMLE_TRANSPORT_PROTOCOL} environment variable, defaulting to {@link
   * #PLAINTEXT} if neither is set.
   */
  public static TransportProtocol fromConfig() {
    String value = System.getProperty(PROPERTY);
    if (value == null || value.isBlank()) {
      value = System.getenv(ENV_VAR);
    }
    if (value == null || value.isBlank()) {
      return PLAINTEXT;
    }
    return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "plaintext" -> PLAINTEXT;
      case "tls", "ssl/tls" -> TLS;
      default ->
          throw new IllegalArgumentException(
              "invalid " + PROPERTY + " value: '" + value + "' (expected 'plaintext' or 'tls')");
    };
  }
}
