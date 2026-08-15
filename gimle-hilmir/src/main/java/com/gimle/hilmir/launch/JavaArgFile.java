package com.gimle.hilmir.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Rewrites a {@code java ...} command into {@code java @argfile} form, identically to {@code
 * com.gimle.hilmir.plan.JavaArgFile} (that copy is package-private to the planner package and this
 * launcher spawns real processes from a different package, so the small rewrite is duplicated here
 * rather than widening the planner's own package-private surface for one cross-package caller). See
 * that class's own javadoc for why every spawn needs this: a spawned command embeds a full
 * classpath -- potentially twice over for an agent, once for itself and once for the nested worker
 * command tail it launches -- which is enough on its own to exceed Windows' hard {@code
 * CreateProcess} command-line length limit as this project's dependency graph grows. The JDK's own
 * {@code @argfile} mechanism (JEP 293) sidesteps that limit identically on every platform, so a
 * launcher applies this unconditionally to every spawn.
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
