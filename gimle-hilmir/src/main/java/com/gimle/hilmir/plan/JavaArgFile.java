package com.gimle.hilmir.plan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Rewrites a {@code java ...} command into {@code java @argfile} form. A spawned process's command
 * embeds a full classpath -- potentially embedded twice over for an agent, once for itself and once
 * for the nested {@code WorkerMain} command tail it launches -- which is enough on its own to
 * exceed Windows' hard {@code CreateProcess} command-line length limit as this project's dependency
 * graph grows ({@code CreateProcess error=206}, "the filename or extension is too long"). The JDK's
 * own {@code @argfile} mechanism (JEP 293) sidesteps that limit identically on every platform by
 * moving the actual argument list off the OS command line entirely, so a launcher applies this
 * unconditionally rather than gating it on OS or on some length threshold -- the safe default as
 * the classpath keeps growing, not a Windows-only patch.
 *
 * <p>Deliberately not applied by {@link LaunchPlanner} itself: {@link LaunchPlanner#plan} produces
 * a {@link ProcessCommand#command()} that still needs {@code runtime.classpath}'s placeholder
 * resolved and, under mtls, an agent's bootstrap token appended (see {@link
 * ProcessCommand#needsBootstrapToken()}) before it is the literal command a process should be
 * spawned with -- this rewrite is the very last step before that spawn, which belongs to a
 * launcher, not to a pure planner.
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
