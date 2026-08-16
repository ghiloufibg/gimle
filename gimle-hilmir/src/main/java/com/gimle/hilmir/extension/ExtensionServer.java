package com.gimle.hilmir.extension;

import com.gimle.hilmir.HilmirException;

/**
 * Resolves the control-plane address an extension verb targets: {@code --server host:port}, falling
 * back to the {@code GIMLE_SERVER} environment variable -- the identical precedence {@code
 * com.gimle.hilmir.release.ReleaseServer} already applies for the six release verbs, duplicated
 * here since that class is package-private to a package this one doesn't reach into.
 */
final class ExtensionServer {

  private ExtensionServer() {}

  static String resolve(ExtensionFlags flags) {
    String server = flags.getOrDefault("--server", System.getenv("GIMLE_SERVER"));
    if (server == null || server.isBlank()) {
      throw new HilmirException(
          "no control-plane server configured (pass --server host:port or set GIMLE_SERVER)");
    }
    return server;
  }
}
