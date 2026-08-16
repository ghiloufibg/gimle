package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.Map;

/**
 * Renders a release verb's result as either a human-readable line (default) or raw JSON ({@code -o
 * json}) -- {@code gimle-cli}'s own {@code -o} convention, reimplemented locally rather than
 * depending on {@code gimle-cli} for it.
 *
 * <p>Public so {@code com.gimle.hilmir.sync} prints each bundle's own outcome (and its own
 * run-level summary) through the identical text/JSON convention every other release verb already
 * uses.
 */
public final class ReleaseOutput {

  private ReleaseOutput() {}

  public static boolean isJson(ReleaseFlags flags) {
    return "json".equals(flags.getOrDefault("-o", "text"));
  }

  public static void printResult(
      boolean json, Map<String, Object> jsonBody, String humanText, PrintStream out) {
    out.println(json ? Json.write(jsonBody) : humanText);
  }
}
