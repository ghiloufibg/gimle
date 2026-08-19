package com.gimle.hilmir.remote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Relays one remote process's output to the operator's terminal, one complete line at a time,
 * prefixed with its machine name -- so {@code --remote} fanning out to every machine at once still
 * reads as N distinguishable interleaved streams rather than one garbled one. {@code
 * PrintStream#println} is already synchronized per call, so concurrent relay threads calling it
 * need no extra locking of their own.
 */
final class RemoteOutput {

  private RemoteOutput() {}

  /**
   * Starts a virtual thread draining {@code in} into {@code out} and returns it, already running.
   */
  static Thread relayPrefixed(final InputStream in, final String prefix, final PrintStream out) {
    final Thread thread =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try (BufferedReader reader =
                      new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                      out.println("[" + prefix + "] " + line);
                    }
                  } catch (final IOException e) {
                    // The stream closes when the remote process exits -- not an error worth
                    // surfacing; the process's own exit code is what callers actually check.
                  }
                });
    thread.start();
    return thread;
  }
}
