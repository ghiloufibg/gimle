package com.gimle.os.localdisk;

import com.gimle.core.exception.GimleVolumeException;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.VolumeRequest;
import com.gimle.os.AllocatedVolume;
import com.gimle.os.VolumeHandle;
import com.gimle.os.VolumeManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guaranteed-minimum, and today the only, {@link VolumeManager}: no replication, no CSI-style
 * pluggable backend, one directory per {@code (tenantId, statefulSetName, instanceIndex,
 * volumeName)} quadruple under {@code <dataRoot>/volumes/}, checked for free space at {@link
 * #allocate} time only -- soft/advisory, matching {@code PortableJvmFlagsResourceLimiter}'s own
 * no-continuous-enforcement posture extended from CPU/memory to disk, not a new precedent.
 */
public final class LocalDiskVolumeManager implements VolumeManager {

  private static final Logger log = LoggerFactory.getLogger(LocalDiskVolumeManager.class);
  private static final String VOLUMES_DIR = "volumes";

  /**
   * The on-disk directory segment for the untenanted namespace -- distinct from any real tenant id
   * (which is always operator-supplied and non-blank), so an untenanted volume can never collide
   * with a real tenant's own directory.
   */
  private static final String UNTENANTED_SEGMENT = "_untenanted";

  private final Path dataRoot;

  public LocalDiskVolumeManager(Path dataRoot) {
    this.dataRoot = dataRoot;
  }

  @Override
  public VolumeHandle allocate(
      Optional<String> tenantId,
      String statefulSetName,
      int instanceIndex,
      String volumeName,
      VolumeRequest request) {
    Path path = instancePath(tenantId, statefulSetName, instanceIndex).resolve(volumeName);
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
    return new VolumeHandle(tenantId, statefulSetName, instanceIndex, volumeName, request);
  }

  @Override
  public Path hostPath(VolumeHandle handle) {
    return instancePath(handle.tenantId(), handle.statefulSetName(), handle.instanceIndex())
        .resolve(handle.volumeName());
  }

  private Path instancePath(Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    return dataRoot
        .resolve(VOLUMES_DIR)
        .resolve(tenantSegment(tenantId))
        .resolve(statefulSetName)
        .resolve(String.valueOf(instanceIndex));
  }

  private static String tenantSegment(Optional<String> tenantId) {
    return tenantId.orElse(UNTENANTED_SEGMENT);
  }

  private static Optional<String> tenantIdFromSegment(String segment) {
    return UNTENANTED_SEGMENT.equals(segment) ? Optional.empty() : Optional.of(segment);
  }

  /**
   * Permanent removal only -- see this interface's own javadoc. Under {@link ReclaimPolicy#DELETE}
   * this deletes the directory tree at {@code hostPath(handle)} recursively; under {@link
   * ReclaimPolicy#RETAIN} (the default) the directory is deliberately left in place -- released
   * from the platform's point of view, but preserved on disk for an operator to inspect or destroy
   * explicitly, so a permanent removal can never silently destroy data unless the module opted in.
   * A directory that's already gone (a second {@code release} for the same handle, or one that was
   * never actually allocated) is a silent no-op, not an error -- matches {@code
   * ResourceLimiter.release}'s own idempotent posture.
   */
  @Override
  public void release(VolumeHandle handle) {
    Path path = hostPath(handle);
    if (handle.request().reclaimPolicy() == ReclaimPolicy.RETAIN) {
      log.info(
          "retaining volume data for tenant {} {}[{}]/{} at {} (reclaimPolicy=RETAIN)",
          handle.tenantId().orElse("-"),
          handle.statefulSetName(),
          handle.instanceIndex(),
          handle.volumeName(),
          path);
      return;
    }
    if (!Files.exists(path)) {
      return;
    }
    deleteRecursively(path, handle.statefulSetName(), handle.instanceIndex());
  }

  /**
   * Walks {@code <dataRoot>/volumes/<tenantSegment>/<statefulSetName>/<index>/<volumeName>} four
   * levels deep -- the exact layout {@link #allocate} creates -- and sums each leaf directory's
   * file sizes. A subtree that isn't a well-formed {@code
   * <tenantSegment>/<name>/<numeric-index>/<volumeName>} quadruple is skipped rather than failing
   * the whole listing: nothing else should ever write under {@code volumes/}, but an operator
   * poking around with a stray file must not make the inventory unreadable.
   */
  @Override
  public List<AllocatedVolume> listAllocated() {
    Path volumesRoot = dataRoot.resolve(VOLUMES_DIR);
    if (!Files.isDirectory(volumesRoot)) {
      return List.of();
    }
    List<AllocatedVolume> volumes = new ArrayList<>();
    try (Stream<Path> tenants = Files.list(volumesRoot)) {
      for (Path tenantDir : tenants.filter(Files::isDirectory).sorted().toList()) {
        Path tenantSegment = tenantDir.getFileName();
        if (tenantSegment == null) {
          continue;
        }
        Optional<String> tenantId = tenantIdFromSegment(tenantSegment.toString());
        try (Stream<Path> names = Files.list(tenantDir)) {
          for (Path nameDir : names.filter(Files::isDirectory).sorted().toList()) {
            Path setName = nameDir.getFileName();
            if (setName == null) {
              continue; // a root path has no file name; listing children of tenantDir never does
            }
            try (Stream<Path> indices = Files.list(nameDir)) {
              for (Path indexDir : indices.filter(Files::isDirectory).sorted().toList()) {
                Path indexName = indexDir.getFileName();
                if (indexName == null) {
                  continue;
                }
                int index;
                try {
                  index = Integer.parseInt(indexName.toString());
                } catch (NumberFormatException e) {
                  continue;
                }
                try (Stream<Path> volumeNames = Files.list(indexDir)) {
                  for (Path volumeDir : volumeNames.filter(Files::isDirectory).sorted().toList()) {
                    Path volumeName = volumeDir.getFileName();
                    if (volumeName == null) {
                      continue;
                    }
                    volumes.add(
                        new AllocatedVolume(
                            tenantId,
                            setName.toString(),
                            index,
                            volumeName.toString(),
                            volumeDir,
                            directorySize(volumeDir)));
                  }
                }
              }
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return List.copyOf(volumes);
  }

  @Override
  public boolean destroy(Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    Path path = instancePath(tenantId, statefulSetName, instanceIndex);
    if (!Files.exists(path)) {
      return false;
    }
    log.warn(
        "destroying volume data for tenant {} {}[{}] at {} (explicit operator destroy)",
        tenantId.orElse("-"),
        statefulSetName,
        instanceIndex,
        path);
    deleteRecursively(path, statefulSetName, instanceIndex);
    return true;
  }

  @Override
  public long usedBytes(Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    Path path = instancePath(tenantId, statefulSetName, instanceIndex);
    if (!Files.isDirectory(path)) {
      return 0;
    }
    return directorySize(path);
  }

  private static long directorySize(Path directory) {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              file -> {
                try {
                  return Files.size(file);
                } catch (IOException e) {
                  return 0; // a file deleted mid-walk just stops counting
                }
              })
          .sum();
    } catch (IOException e) {
      return 0;
    }
  }

  private void deleteRecursively(Path path, String statefulSetName, int instanceIndex) {
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
      throw GimleVolumeException.releaseFailed(statefulSetName, instanceIndex, e);
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
