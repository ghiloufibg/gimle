package com.gimle.core.vessel;

import java.util.List;

/**
 * A bundle artifact's launch descriptor, the parsed form of the {@code gimle-entrypoint.yaml} file
 * at the archive root of every {@code BUNDLE}-kind artifact: the fixed argv prefix to execute
 * ({@code command}, always run in full -- a workload manifest's own {@code vessel.args} may only
 * append to it, never override it) and the working directory to launch in, relative to the unpacked
 * bundle root.
 *
 * <p>{@code workdir} is validated here structurally (relative, forward slashes only, no {@code ..}
 * segment -- neither has a legitimate use inside a self-contained bundle) and re-checked against
 * the actual filesystem by the node agent immediately before spawn. Both checks exist because this
 * record is constructed from untrusted bytes: the artifact registry deliberately never inspects
 * what it stores, so a hand-crafted archive can reach an agent without ever passing through the
 * CLI's own generation path.
 */
public record VesselEntrypoint(List<String> command, String workdir) {

  /** The reserved file name this descriptor is read from at the archive root. */
  public static final String FILE_NAME = "gimle-entrypoint.yaml";

  /** The default working directory: the unpacked bundle root itself. */
  public static final String DEFAULT_WORKDIR = ".";

  public VesselEntrypoint {
    command = List.copyOf(command);
    if (command.isEmpty()) {
      throw new IllegalArgumentException("entrypoint command must not be empty");
    }
    for (String argument : command) {
      if (argument == null || argument.isBlank()) {
        throw new IllegalArgumentException("entrypoint command entries must be non-blank strings");
      }
    }
    if (workdir == null || workdir.isBlank()) {
      workdir = DEFAULT_WORKDIR;
    }
    requireInsideBundle(workdir);
  }

  private static void requireInsideBundle(String workdir) {
    if (workdir.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          "entrypoint workdir must use forward slashes: " + workdir);
    }
    if (workdir.startsWith("/")) {
      throw new IllegalArgumentException(
          "entrypoint workdir must be relative to the bundle root: " + workdir);
    }
    for (String segment : workdir.split("/")) {
      if ("..".equals(segment)) {
        throw new IllegalArgumentException(
            "entrypoint workdir must not escape the bundle root: " + workdir);
      }
    }
  }
}
