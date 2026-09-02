package com.gimle.mimir.manifest;

/**
 * The transport a Service's traffic is relayed over, the {@code Service.spec.ports[].protocol}
 * analogue. {@code TCP} is what a manifest declaring no protocol gets, matching Kubernetes' own
 * default and every Service that existed before this was expressible.
 *
 * <p>The distinction is real work for {@code gimle-bifrost} rather than a label: a TCP listener
 * accepts connections and relays a byte stream in both directions for as long as both ends hold it
 * open, while a UDP listener has no connection to accept at all and must instead map each reply
 * back to whichever client sent the datagram it answers.
 */
public enum ServiceProtocol {
  TCP,
  UDP
}
