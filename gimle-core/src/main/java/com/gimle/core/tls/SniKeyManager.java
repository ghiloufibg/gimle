package com.gimle.core.tls;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Picks which certificate a TLS server presents from the hostname the client asked for in its SNI
 * extension, so one listener can serve several virtual hosts on one port without every host but one
 * failing the client's own hostname verification.
 *
 * <p>Holds its own alias-to-key-material map rather than delegating to a {@code
 * KeyManagerFactory}-produced manager: the JDK's own managers mangle aliases differently depending
 * on which algorithm is configured ({@code SunX509} hands back raw keystore aliases, {@code PKIX}
 * hands back index-prefixed ones), so an alias chosen here would not reliably be an alias the
 * delegate could then resolve back to a key.
 *
 * <p>Selection never rejects a connection. A client that sends no SNI at all (an IP-literal
 * connection, or an old client) and a client naming a hostname with no binding both get the default
 * certificate -- the same one a single-certificate listener would have presented -- rather than a
 * failed handshake. That is deliberate rather than installing an {@code SNIMatcher}: a matcher
 * refuses the connection outright, which would break the host-unconstrained fallback routing such a
 * listener is expected to keep serving.
 */
final class SniKeyManager extends X509ExtendedKeyManager {

  private static final String DEFAULT_ALIAS = "gimle-default";
  private static final String HOST_ALIAS_PREFIX = "gimle-sni:";

  private final Map<String, KeyEntry> byAlias = new LinkedHashMap<>();

  SniKeyManager(KeyEntry defaultEntry, Map<String, KeyEntry> byHostname) {
    byAlias.put(DEFAULT_ALIAS, defaultEntry);
    byHostname.forEach(
        (hostname, entry) ->
            byAlias.put(HOST_ALIAS_PREFIX + hostname.toLowerCase(Locale.ROOT), entry));
  }

  @Override
  public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
    SSLSession handshakeSession =
        socket instanceof SSLSocket sslSocket ? sslSocket.getHandshakeSession() : null;
    return chooseAlias(keyType, handshakeSession);
  }

  @Override
  public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
    return chooseAlias(keyType, engine == null ? null : engine.getHandshakeSession());
  }

  @Override
  public String[] getServerAliases(String keyType, Principal[] issuers) {
    return byAlias.entrySet().stream()
        .filter(entry -> matchesKeyType(keyType, entry.getValue()))
        .map(Map.Entry::getKey)
        .toArray(String[]::new);
  }

  /**
   * This context is built for a listener, but an {@code SSLContext} is usable in both directions,
   * so the client side answers with the default identity rather than nothing -- there is no server
   * name to select on when this side is the one connecting.
   */
  @Override
  public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
    return defaultAliasFor(keyTypes);
  }

  @Override
  public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
    return defaultAliasFor(keyTypes);
  }

  @Override
  public String[] getClientAliases(String keyType, Principal[] issuers) {
    return matchesKeyType(keyType, byAlias.get(DEFAULT_ALIAS))
        ? new String[] {DEFAULT_ALIAS}
        : new String[0];
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    KeyEntry entry = byAlias.get(alias);
    return entry == null ? null : entry.chain().clone();
  }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    KeyEntry entry = byAlias.get(alias);
    return entry == null ? null : entry.privateKey();
  }

  private String chooseAlias(String keyType, SSLSession handshakeSession) {
    Optional<String> requested = requestedHostname(handshakeSession);
    if (requested.isPresent()) {
      String alias = HOST_ALIAS_PREFIX + requested.get();
      if (matchesKeyType(keyType, byAlias.get(alias))) {
        return alias;
      }
    }
    return matchesKeyType(keyType, byAlias.get(DEFAULT_ALIAS)) ? DEFAULT_ALIAS : null;
  }

  private String defaultAliasFor(String[] keyTypes) {
    if (keyTypes == null) {
      return DEFAULT_ALIAS;
    }
    for (String keyType : keyTypes) {
      if (matchesKeyType(keyType, byAlias.get(DEFAULT_ALIAS))) {
        return DEFAULT_ALIAS;
      }
    }
    return null;
  }

  private static Optional<String> requestedHostname(SSLSession handshakeSession) {
    if (!(handshakeSession instanceof ExtendedSSLSession extended)) {
      return Optional.empty();
    }
    return extended.getRequestedServerNames().stream()
        .filter(SNIHostName.class::isInstance)
        .map(name -> ((SNIHostName) name).getAsciiName().toLowerCase(Locale.ROOT))
        .findFirst();
  }

  /**
   * Returning an alias whose key cannot sign what the handshake asked for aborts that handshake
   * outright, so a mismatch answers null instead and lets the peer's next requested key type be
   * tried.
   */
  private static boolean matchesKeyType(String keyType, KeyEntry entry) {
    if (entry == null) {
      return false;
    }
    return keyType == null || keyType.equalsIgnoreCase(entry.privateKey().getAlgorithm());
  }

  /** One virtual host's own identity: its private key and the chain presented alongside it. */
  record KeyEntry(PrivateKey privateKey, X509Certificate[] chain) {}
}
