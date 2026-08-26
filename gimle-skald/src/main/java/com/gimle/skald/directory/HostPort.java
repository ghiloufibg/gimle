package com.gimle.skald.directory;

/**
 * One live endpoint of a Service as the directory caches it: the host (a dotted-decimal IPv4
 * literal per the control plane's endpoint contract) and the port that specific endpoint listens
 * on. Carried per endpoint rather than once per service because an {@code A} answer needs only
 * hosts while an {@code SRV} answer needs each endpoint's own port -- two endpoints of one service
 * can legitimately listen on different ports.
 */
public record HostPort(String host, int port) {

  public HostPort {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port out of range: " + port);
    }
  }
}
