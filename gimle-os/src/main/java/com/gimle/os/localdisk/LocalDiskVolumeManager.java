package com.gimle.os.localdisk;

import com.gimle.core.exception.GimleVolumeException;
import com.gimle.core.module.VolumeRequest;
import com.gimle.os.VolumeHandle;
import com.gimle.os.VolumeManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The guaranteed-minimum, and today the only, {@link VolumeManager}: no replication, no CSI-style
 * pluggable backend, one directory per {@code (statefulSetName, instanceIndex)} pair under {@code
 * <dataRoot>/volumes/}, checked for free space at {@link #allocate} time only -- soft/advisory,
 * matching {@code PortableJvmFlagsResourceLimiter}'s own no-continuous-enforcement posture extended
 * from CPU/memory to disk, not a new precedent.
 */
public final class LocalDiskVolumeManager implements VolumeManager {

  private final Path dataRoot;

  public LocalDiskVolumeManager(Path dataRoot) {
    this.dataRoot = dataRoot;
  }

  @Override
  public VolumeHandle allocate(String statefulSetName, int instanceIndex, VolumeRequest request) {
    Path path = hostPath(statefulSetName, instanceIndex);
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      throw GimleVolumeException.allocationFailed(statefulSetName, instanceIndex, e);
    }
    long usableBytes = usableSpace(path, statefulSetName, instanceIndex);
    if (usableBytes < request.sizeBytes()) {
      throw GimleVolumeException.insufficientSpace(
          statefulSetName, instanceIndex, request.sizeBytes(), usableBytes);
    }
    return new VolumeHandle(statefulSetName, instanceIndex, request);
  }

  @Override
  public Path hostPath(VolumeHandle handle) {
    return hostPath(handle.statefulSetName(), handle.instanceIndex());
  }

  private Path hostPath(String statefulSetName, int instanceIndex) {
    return dataRoot
        .resolve("volumes")
        .resolve(statefulSetName)
        .resolve(String.valueOf(instanceIndex));
  }

  /**
   * Permanent removal only -- see this interface's own javadoc. Deletes the directory tree at
   * {@code hostPath(handle)} recursively; a directory that's already gone (a second {@code release}
   * for the same handle, or one that was never actually allocated) is a silent no-op, not an error
   * -- matches {@code ResourceLimiter.release}'s own idempotent posture.
   */
  @Override
  public void release(VolumeHandle handle) {
    Path path = hostPath(handle);
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException | UncheckedIOException e) {
      throw GimleVolumeException.releaseFailed(handle.statefulSetName(), handle.instanceIndex(), e);
    }
  }

  private static long usableSpace(Path path, String statefulSetName, int instanceIndex) {
    try {
      FileStore store = Files.getFileStore(path);
      return store.getUsableSpace();
    } catch (IOException e) {
      throw GimleVolumeException.allocationFailed(statefulSetName, instanceIndex, e);
    }
  }
}
