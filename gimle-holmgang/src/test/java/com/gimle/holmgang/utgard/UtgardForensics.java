package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Captures each {@code hilmir} invocation's own transcript to a file under the scenario's work
 * directory, so a later assertion failing several steps downstream still leaves every earlier
 * command's own stdout/stderr on disk for the post-mortem -- the same "process logs are the
 * forensic artifact" posture {@code WorkDirs} documents for {@code GimleCluster}'s real spawned
 * processes, applied here to commands run inside a container instead.
 */
final class UtgardForensics {

  private UtgardForensics() {}

  static void record(final Path workDir, final String label, final UtgardExecResult result) {
    try {
      Files.createDirectories(workDir);
      Files.writeString(
          workDir.resolve(label + ".log"),
          UtgardExec.describe(result, label) + "\n",
          StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HolmgangException("failed writing forensic log for " + label, e);
    }
  }
}
