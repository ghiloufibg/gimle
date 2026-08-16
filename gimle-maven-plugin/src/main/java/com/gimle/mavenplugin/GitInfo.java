package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The working tree's current short commit sha and branch name, captured via real {@code git
 * rev-parse} subprocesses. Both are best-effort: no git binary, no repository, or a timed-out call
 * all just yield empty values -- run identity degrades gracefully instead of failing the build.
 */
record GitInfo(Optional<String> shortSha, Optional<String> branch) {

  static GitInfo capture(Path workingDirectory) {
    return new GitInfo(
        revParse(workingDirectory, "--short", "HEAD"),
        revParse(workingDirectory, "--abbrev-ref", "HEAD"));
  }

  private static Optional<String> revParse(Path workingDirectory, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "rev-parse"));
    command.addAll(List.of(args));
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(workingDirectory.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      return process.exitValue() == 0 && !output.isEmpty() ? Optional.of(output) : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
