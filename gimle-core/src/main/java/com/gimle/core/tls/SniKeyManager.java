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
 *
 * <p>The per-hostname binding set can be replaced in place via {@link #updateHostBindings} without
 * rebuilding this key manager or the {@code SSLContext}/listener it's installed on -- selection
 * already runs fresh on every new handshake, so there is nothing about an already-bound socket that
 * needs to change for a config-driven certificate update to take effect.
 */
final class SniKeyManager extends X509ExtendedKeyManager {

  private static final String DEFAULT_ALIAS = "gimle-default";
  private static final String HOST_ALIAS_PREFIX = "gimle-sni:";

  private final KeyEntry defaultEntry;

  // Read on every handshake (chooseAlias et al.), written only by updateHostBindings -- a plain
  // volatile reference swap is enough: a handshake in flight keeps whatever fully-built map it
  // already read, and the very next handshake sees the new one, with no lock needed on either side.
  private volatile Map<String, KeyEntry> byAlias;

  SniKeyManager(KeyEntry defaultEntry, Map<String, KeyEntry> byHostname) {
    this.defaultEntry = defaultEntry;
    this.byAlias = buildAliasMap(defaultEntry, byHostname);
  }

  private static Map<String, KeyEntry> buildAliasMap(
      KeyEntry defaultEntry, Map<String, KeyEntry> byHostname) {
    Map<String, KeyEntry> map = new LinkedHashMap<>();
    map.put(DEFAULT_ALIAS, defaultEntry);
    byHostname.forEach(
        (hostname, entry) -> map.put(HOST_ALIAS_PREFIX + hostname.toLowerCase(Locale.ROOT), entry));
    return Map.copyOf(map);
  }

  /**
   * Replaces the live per-hostname bindings this key manager selects among -- what lets a listener
   * built on this key manager pick up a config-driven change to its virtual-host certificates
   * without rebinding: SNI selection already happens fresh on every new handshake (see {@link
   * #chooseAlias}), so swapping which certificate an alias resolves to is enough on its own. An
   * already-established connection is unaffected -- a TLS session's negotiated certificate never
   * changes mid-connection -- and the default alias this key manager started with is untouched.
   */
  void updateHostBindings(Map<String, KeyEntry> byHostname) {
    byAlias = buildAliasMap(defaultEntry, byHostname);
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
