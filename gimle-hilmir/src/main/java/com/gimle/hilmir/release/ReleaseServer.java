package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;

/**
 * Resolves the control-plane address a release verb targets: {@code --server host:port}, falling
 * back to the {@code GIMLE_SERVER} environment variable -- the same flag-over-environment-variable
 * precedence {@code gimle-cli} itself uses for the identical flag.
 */
final class ReleaseServer {

  private ReleaseServer() {}

  static String resolve(ReleaseFlags flags) {
    String server = flags.getOrDefault("--server", System.getenv("GIMLE_SERVER"));
    if (server == null || server.isBlank()) {
      throw new HilmirException(
          "no control-plane server configured (pass --server host:port or set GIMLE_SERVER)");
    }
    return server;
  }
}
