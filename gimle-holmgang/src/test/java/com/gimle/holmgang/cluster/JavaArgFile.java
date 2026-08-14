package com.gimle.holmgang.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Rewrites a {@code java ...} command into {@code java @argfile} form. Every process this module
 * spawns shares the harness's own full test classpath (see {@link GimleCluster}'s own class
 * javadoc), and the AGENT spawn embeds that classpath twice over -- once for itself, once for the
 * nested {@code WorkerMain} command tail it launches -- which is enough on its own to exceed
 * Windows' hard {@code CreateProcess} command-line length limit as this project's dependency graph
 * has grown ({@code CreateProcess error=206}, "the filename or extension is too long"; store,
 * Muninn, Fafnir, and the control plane each embed the classpath only once and stay under it, which
 * is why only AGENT ever failed). The JDK's own {@code @argfile} mechanism (JEP 293) sidesteps that
 * limit identically on every platform by moving the actual argument list off the OS command line
 * entirely, so this is applied unconditionally rather than gated on OS or on some length threshold
 * -- the safe default as the classpath keeps growing, not a Windows-only patch.
 */
final class JavaArgFile {

  private JavaArgFile() {}

  /**
   * {@code command}'s first element must be the java executable; every remaining element becomes
   * one line of {@code argFile}, individually quoted (JEP 293's own quoting rules: wrap in double
   * quotes, backslash-escape embedded {@code \} and {@code "}). Returns {@code [javaExecutable,
   * "@argFile"]} -- the only two tokens the OS itself ever sees on the real command line.
   */
  static List<String> rewrite(final List<String> command, final Path argFile) {
    final String javaExecutable = command.get(0);
    final StringBuilder contents = new StringBuilder();
    for (final String arg : command.subList(1, command.size())) {
      contents.append(quote(arg)).append('\n');
    }
    try {
      Files.writeString(argFile, contents, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed writing java argfile " + argFile, e);
    }
    return List.of(javaExecutable, "@" + argFile.toAbsolutePath());
  }

  private static String quote(final String arg) {
    final StringBuilder quoted = new StringBuilder(arg.length() + 2);
    quoted.append('"');
    for (int i = 0; i < arg.length(); i++) {
      final char c = arg.charAt(i);
      if (c == '\\' || c == '"') {
        quoted.append('\\');
      }
      quoted.append(c);
    }
    quoted.append('"');
    return quoted.toString();
  }
}
