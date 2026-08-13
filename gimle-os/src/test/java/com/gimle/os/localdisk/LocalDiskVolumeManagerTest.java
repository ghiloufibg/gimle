package com.gimle.os.localdisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleVolumeException;
import com.gimle.core.module.VolumeRequest;
import com.gimle.os.VolumeHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskVolumeManagerTest {

  @TempDir Path tempDir;

  @Test
  void allocate_creates_a_directory_keyed_by_statefulset_name_and_index() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024, "/data");

    VolumeHandle handle = manager.allocate("orders-statefulset", 2, request);

    Path expected = tempDir.resolve("volumes").resolve("orders-statefulset").resolve("2");
    assertEquals(expected, manager.hostPath(handle));
    assertTrue(Files.isDirectory(expected));
  }

  @Test
  void allocate_is_idempotent_for_the_same_index() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024, "/data");

    VolumeHandle first = manager.allocate("orders-statefulset", 0, request);
    Path path = manager.hostPath(first);
    Files.writeString(path.resolve("marker.txt"), "still here");

    manager.allocate("orders-statefulset", 0, request);

    assertTrue(
        Files.exists(path.resolve("marker.txt")),
        "re-allocating the same index must not wipe data already written there");
  }

  @Test
  void different_indices_and_statefulsets_get_distinct_directories() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024, "/data");

    Path a0 = manager.hostPath(manager.allocate("a", 0, request));
    Path a1 = manager.hostPath(manager.allocate("a", 1, request));
    Path b0 = manager.hostPath(manager.allocate("b", 0, request));

    assertFalse(a0.equals(a1));
    assertFalse(a0.equals(b0));
    assertFalse(a1.equals(b0));
  }

  @Test
  void allocate_throws_when_the_request_exceeds_usable_space() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    // No real disk has an exabyte free -- a deliberately absurd request exercises the rejection
    // path without needing to fill a real filesystem.
    VolumeRequest absurd = new VolumeRequest(Long.MAX_VALUE / 2, "/data");

    assertThrows(
        GimleVolumeException.class, () -> manager.allocate("orders-statefulset", 0, absurd));
  }

  @Test
  void release_deletes_the_volume_directory_and_its_contents() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024, "/data");
    VolumeHandle handle = manager.allocate("orders-statefulset", 0, request);
    Path path = manager.hostPath(handle);
    Files.writeString(path.resolve("data.db"), "some persisted state");

    manager.release(handle);

    assertFalse(Files.exists(path));
  }

  @Test
  void release_of_a_never_allocated_handle_is_a_silent_no_op() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeHandle neverAllocated =
        new VolumeHandle("orders-statefulset", 5, new VolumeRequest(1024, "/data"));

    manager.release(neverAllocated); // must not throw
  }
}
