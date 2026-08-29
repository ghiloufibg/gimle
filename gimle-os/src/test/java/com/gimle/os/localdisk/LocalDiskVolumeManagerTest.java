package com.gimle.os.localdisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleVolumeException;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.VolumeRequest;
import com.gimle.os.AllocatedVolume;
import com.gimle.os.VolumeHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskVolumeManagerTest {

  @TempDir Path tempDir;

  private static final Optional<String> NO_TENANT = Optional.empty();

  @Test
  void allocate_creates_a_directory_keyed_by_statefulset_name_index_and_volume_name() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);

    VolumeHandle handle = manager.allocate(NO_TENANT, "orders-statefulset", 2, "data", request);

    Path expected =
        tempDir
            .resolve("volumes")
            .resolve("_untenanted")
            .resolve("orders-statefulset")
            .resolve("2")
            .resolve("data");
    assertEquals(expected, manager.hostPath(handle));
    assertTrue(Files.isDirectory(expected));
  }

  @Test
  void allocate_is_idempotent_for_the_same_index() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);

    VolumeHandle first = manager.allocate(NO_TENANT, "orders-statefulset", 0, "data", request);
    Path path = manager.hostPath(first);
    Files.writeString(path.resolve("marker.txt"), "still here");

    manager.allocate(NO_TENANT, "orders-statefulset", 0, "data", request);

    assertTrue(
        Files.exists(path.resolve("marker.txt")),
        "re-allocating the same index must not wipe data already written there");
  }

  @Test
  void different_indices_and_statefulsets_get_distinct_directories() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);

    Path a0 = manager.hostPath(manager.allocate(NO_TENANT, "a", 0, "data", request));
    Path a1 = manager.hostPath(manager.allocate(NO_TENANT, "a", 1, "data", request));
    Path b0 = manager.hostPath(manager.allocate(NO_TENANT, "b", 0, "data", request));

    assertFalse(a0.equals(a1));
    assertFalse(a0.equals(b0));
    assertFalse(a1.equals(b0));
  }

  @Test
  void two_tenants_with_an_identically_named_statefulset_get_distinct_directories()
      throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);

    Path tenantA =
        manager.hostPath(manager.allocate(Optional.of("tenant-a"), "db", 0, "data", request));
    Path tenantB =
        manager.hostPath(manager.allocate(Optional.of("tenant-b"), "db", 0, "data", request));
    Files.writeString(tenantA.resolve("marker.txt"), "tenant-a's own data");

    assertFalse(
        tenantA.equals(tenantB),
        "two tenants' identically-named statefulsets must never share a volume directory");
    assertFalse(
        Files.exists(tenantB.resolve("marker.txt")),
        "tenant-b must not see tenant-a's data through a shared statefulSetName");
  }

  @Test
  void allocate_throws_when_the_request_exceeds_usable_space() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    // No real disk has an exabyte free -- a deliberately absurd request exercises the rejection
    // path without needing to fill a real filesystem.
    VolumeRequest absurd = new VolumeRequest(Long.MAX_VALUE / 2);

    assertThrows(
        GimleVolumeException.class,
        () -> manager.allocate(NO_TENANT, "orders-statefulset", 0, "data", absurd));
  }

  @Test
  void release_under_delete_policy_deletes_the_volume_directory_and_its_contents()
      throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024, ReclaimPolicy.DELETE);
    VolumeHandle handle = manager.allocate(NO_TENANT, "orders-statefulset", 0, "data", request);
    Path path = manager.hostPath(handle);
    Files.writeString(path.resolve("data.db"), "some persisted state");

    manager.release(handle);

    assertFalse(Files.exists(path));
  }

  @Test
  void release_under_default_retain_policy_leaves_the_data_on_disk() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);
    VolumeHandle handle = manager.allocate(NO_TENANT, "orders-statefulset", 0, "data", request);
    Path path = manager.hostPath(handle);
    Files.writeString(path.resolve("data.db"), "some persisted state");

    manager.release(handle);

    assertTrue(
        Files.exists(path.resolve("data.db")),
        "RETAIN (the default) must leave a released volume's data in place");
  }

  @Test
  void list_allocated_reports_every_volume_directory_with_its_used_bytes() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);
    Path a0 = manager.hostPath(manager.allocate(NO_TENANT, "orders", 0, "data", request));
    manager.allocate(NO_TENANT, "sessions", 2, "data", request);
    Files.writeString(a0.resolve("data.db"), "0123456789");

    List<AllocatedVolume> volumes = manager.listAllocated();

    assertEquals(2, volumes.size());
    AllocatedVolume orders =
        volumes.stream()
            .filter(v -> v.statefulSetName().equals("orders"))
            .findFirst()
            .orElseThrow();
    assertEquals(NO_TENANT, orders.tenantId());
    assertEquals(0, orders.instanceIndex());
    assertEquals(10, orders.usedBytes());
    AllocatedVolume sessions =
        volumes.stream()
            .filter(v -> v.statefulSetName().equals("sessions"))
            .findFirst()
            .orElseThrow();
    assertEquals(2, sessions.instanceIndex());
    assertEquals(0, sessions.usedBytes());
  }

  @Test
  void list_allocated_reports_the_owning_tenant_for_a_tenanted_volume() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);
    manager.allocate(Optional.of("tenant-a"), "db", 0, "data", request);

    List<AllocatedVolume> volumes = manager.listAllocated();

    assertEquals(1, volumes.size());
    assertEquals(Optional.of("tenant-a"), volumes.get(0).tenantId());
  }

  @Test
  void a_retained_orphan_still_lists_until_explicitly_destroyed() throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeHandle handle = manager.allocate(NO_TENANT, "orders", 0, "data", new VolumeRequest(1024));
    Path path = manager.hostPath(handle);
    Files.writeString(path.resolve("data.db"), "some persisted state");
    manager.release(handle); // RETAIN default: data stays

    assertEquals(1, manager.listAllocated().size());

    manager.destroy(NO_TENANT, "orders", 0);

    assertFalse(Files.exists(path));
    assertTrue(manager.listAllocated().isEmpty());
  }

  @Test
  void two_named_volumes_of_one_instance_get_distinct_directories_and_one_destroy_reclaims_both()
      throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);
    Path data = manager.hostPath(manager.allocate(NO_TENANT, "orders", 0, "data", request));
    Path logs = manager.hostPath(manager.allocate(NO_TENANT, "orders", 0, "logs", request));
    Files.writeString(data.resolve("a.db"), "abc");
    Files.writeString(logs.resolve("b.log"), "xyz");

    assertFalse(data.equals(logs));
    List<AllocatedVolume> volumes = manager.listAllocated();
    assertEquals(2, volumes.size());
    assertTrue(volumes.stream().anyMatch(v -> v.volumeName().equals("data")));
    assertTrue(volumes.stream().anyMatch(v -> v.volumeName().equals("logs")));
    assertEquals(6, manager.usedBytes(NO_TENANT, "orders", 0));

    manager.destroy(NO_TENANT, "orders", 0);

    assertTrue(manager.listAllocated().isEmpty());
  }

  @Test
  void destroying_one_tenants_volume_leaves_another_tenants_identically_named_one_intact()
      throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeRequest request = new VolumeRequest(1024);
    Path tenantAPath =
        manager.hostPath(manager.allocate(Optional.of("tenant-a"), "db", 0, "data", request));
    manager.allocate(Optional.of("tenant-b"), "db", 0, "data", request);

    manager.destroy(Optional.of("tenant-a"), "db", 0);

    assertFalse(Files.exists(tenantAPath));
    assertEquals(1, manager.listAllocated().size());
    assertEquals(Optional.of("tenant-b"), manager.listAllocated().get(0).tenantId());
  }

  @Test
  void destroy_of_a_nonexistent_volume_is_a_silent_no_op() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    manager.destroy(NO_TENANT, "never-allocated", 7); // must not throw
  }

  @Test
  void used_bytes_reports_zero_for_a_missing_volume_and_real_sizes_for_a_present_one()
      throws IOException {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    assertEquals(0, manager.usedBytes(NO_TENANT, "orders", 0));

    Path path =
        manager.hostPath(manager.allocate(NO_TENANT, "orders", 0, "data", new VolumeRequest(1024)));
    Files.writeString(path.resolve("a.txt"), "12345");
    Files.createDirectories(path.resolve("nested"));
    Files.writeString(path.resolve("nested/b.txt"), "123");

    assertEquals(8, manager.usedBytes(NO_TENANT, "orders", 0));
  }

  @Test
  void release_of_a_never_allocated_handle_is_a_silent_no_op() {
    LocalDiskVolumeManager manager = new LocalDiskVolumeManager(tempDir);
    VolumeHandle neverAllocated =
        new VolumeHandle(
            NO_TENANT,
            "orders-statefulset",
            5,
            "data",
            new VolumeRequest(1024, ReclaimPolicy.DELETE));

    manager.release(neverAllocated); // must not throw
  }
}
