package com.gimle.core.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a file holding sensitive material -- a private key, a signing key, a one-time credential,
 * a secret value rendered into a hosted module's own file mount -- with owner-read/write-only
 * permissions applied atomically at creation, never as a follow-up {@code chmod}. A separate
 * write-then-restrict pair (an ordinary {@code Files.write} followed by {@code
 * Files.setPosixFilePermissions}) leaves the file briefly, and permanently if the process dies
 * between the two calls, at whatever the process's default umask allows -- commonly group/world-
 * readable on a stock Linux host. {@link Files#createFile(Path,
 * java.nio.file.attribute.FileAttribute[])} sets the requested mode as part of the same syscall
 * that creates the file, so there is no window between "file exists" and "file is owner-only" for a
 * local co-resident account to win.
 *
 * <p>Falls back to {@code java.io.File}'s portable permission setters on a filesystem with no POSIX
 * permissions view (Windows, local development only -- every real deployment target this platform
 * runs on is POSIX). That fallback still can't be applied atomically at creation the way the POSIX
 * branch is, so a file created on such a filesystem is briefly visible at default permissions
 * regardless -- an accepted, local-development-only residual, not the exposure this class exists to
 * close.
 */
public final class OwnerOnlyFiles {

  private static final Logger log = LoggerFactory.getLogger(OwnerOnlyFiles.class);

  private OwnerOnlyFiles() {}

  /**
   * Writes {@code content} to {@code path}: creates it with owner-only permissions atomically if it
   * doesn't exist yet, or overwrites its content in place (permissions left untouched) if it does
   * -- so a caller that repeatedly rewrites the same path (a key rotation's {@code .active}
   * sidecar, a renewed leaf certificate's key file, a secret value re-rendered on every reconcile)
   * never reopens the exposure window the first write already closed.
   */
  public static void write(Path path, byte[] content) throws IOException {
    if (!Files.exists(path)) {
      createRestricted(path);
    }
    Files.write(path, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void createRestricted(Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      return;
    }
    Files.createFile(path);
    if (!restrictPortable(path)) {
      log.warn(
          "filesystem at {} does not support POSIX permissions and its portable owner-only"
              + " restriction failed; file was created without owner-only restriction (expected"
              + " only in local Windows development -- every real deployment target restricts"
              + " this)",
          path);
    }
  }

  /**
   * Every setter below must run regardless of whether an earlier one succeeded -- a failed {@code
   * setReadable(false, false)} shouldn't skip the attempt to also clear write/execute or restore
   * owner access. Each call is captured in its own local first, then combined with plain {@code
   * &&}, rather than short-circuiting {@code &&} directly between calls (which would skip later
   * setters the moment one returns {@code false}) or the bitwise {@code &} that would run
   * everything but reads as an accidental typo for {@code &&}.
   */
  private static boolean restrictPortable(Path path) {
    File file = path.toFile();
    boolean clearedGroupOtherRead = file.setReadable(false, false);
    boolean clearedGroupOtherWrite = file.setWritable(false, false);
    boolean clearedGroupOtherExecute = file.setExecutable(false, false);
    boolean restoredOwnerRead = file.setReadable(true, true);
    boolean restoredOwnerWrite = file.setWritable(true, true);
    return clearedGroupOtherRead
        && clearedGroupOtherWrite
        && clearedGroupOtherExecute
        && restoredOwnerRead
        && restoredOwnerWrite;
  }
}
