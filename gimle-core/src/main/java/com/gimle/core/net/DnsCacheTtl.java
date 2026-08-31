package com.gimle.core.net;

import java.security.Security;

/**
 * Caps how long the JVM's own DNS resolver caches a successful lookup, via the {@code
 * networkaddress.cache.ttl} {@link Security} property -- the standard, JDK-native mechanism for
 * this (no {@code java.net.spi.InetAddressResolverProvider} needed: nothing here wants a custom
 * resolver, only a shorter cache lifetime on the JDK's own default one).
 *
 * <p>{@code gimle-skald} answers every DNS query with a deliberately short TTL specifically so a
 * caller re-resolves soon after endpoint churn -- but by default the JDK caches a successful lookup
 * for far longer than that, which silently defeats Skald's own freshness promise for any {@code
 * java.net.http.HttpClient} built in this platform, since {@code HttpClient} resolves through the
 * JVM's shared resolver cache like everything else. {@code gimle-core} can't reference Skald's own
 * answer-TTL constant directly (the module dependency runs the other way), so {@link #TTL_SECONDS}
 * is kept equal to it by hand: shorter would make DNS chattier for no freshness gain beyond what
 * Skald already promises, longer would let a resolved address outlive that promise.
 *
 * <p>Applied unconditionally, regardless of whether a given deployment actually uses Skald for name
 * resolution: a short resolver-cache TTL is harmless in front of a numeric address or a DNS name
 * that never moves (it only makes resolution slightly more frequent), so there is no separate
 * opt-in flag gating this.
 *
 * <p>Must run before the first DNS resolution happens in the JVM -- in practice, as the first thing
 * a process's own {@code Main.main()} does, before any {@code HttpClient} is built. Calling {@link
 * #apply()} more than once is safe and has no additional effect: {@link Security#setProperty} is a
 * plain last-write-wins assignment, not a listener registration.
 */
public final class DnsCacheTtl {

  /**
   * Seconds a resolved address may be cached for. Mirrors {@code gimle-skald}'s own DNS-answer TTL
   * -- update both together if that value ever changes.
   */
  private static final int TTL_SECONDS = 5;

  private DnsCacheTtl() {}

  public static void apply() {
    Security.setProperty("networkaddress.cache.ttl", Integer.toString(TTL_SECONDS));
  }
}
