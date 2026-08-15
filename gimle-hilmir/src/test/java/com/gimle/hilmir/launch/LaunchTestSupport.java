package com.gimle.hilmir.launch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

/** Shared plumbing for launch-package tests that spawn a real, cheap OS process. */
final class LaunchTestSupport {

  private LaunchTestSupport() {}

  static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  static String testClasspath() {
    return System.getProperty("java.class.path");
  }

  /**
   * A loopback port free at the moment of the call -- released immediately for the caller to bind.
   */
  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
