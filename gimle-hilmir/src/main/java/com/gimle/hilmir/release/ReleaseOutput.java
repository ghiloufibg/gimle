package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.Map;

/**
 * Renders a release verb's result as either a human-readable line (default) or raw JSON ({@code -o
 * json}) -- {@code gimle-cli}'s own {@code -o} convention, reimplemented locally rather than
 * depending on {@code gimle-cli} for it.
 */
final class ReleaseOutput {

  private ReleaseOutput() {}

  static boolean isJson(ReleaseFlags flags) {
    return "json".equals(flags.getOrDefault("-o", "text"));
  }

  static void printResult(
      boolean json, Map<String, Object> jsonBody, String humanText, PrintStream out) {
    out.println(json ? Json.write(jsonBody) : humanText);
  }
}
