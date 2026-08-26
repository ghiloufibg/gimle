package com.gimle.os;

import com.gimle.core.module.VolumeRequest;
import java.nio.file.Path;
import java.util.List;

/**
 * Allocates a {@code StatefulSet}-shaped instance's persistent local-disk storage, structurally
 * parallel to {@link ResourceLimiter}: one implementation today ({@code LocalDiskVolumeManager}),
 * local-disk-backed only -- no replication, no CSI-style pluggable backend. Single-node local disk
 * is the deliberate scope for persistent storage here, not a stand-in for a distributed volume
 * system; a sticky-placement instance's data lives on the machine it's scheduled to, and nothing in
 * this interface assumes otherwise.
 *
 * <p>{@code release} is called only on permanent removal (a {@code StatefulSet} index scaled down
 * for good, or the whole spec deleted) -- never on an ordinary reschedule or rolling-update
 * teardown-then-replace, since the entire point of sticky placement is that the data at {@code
 * hostPath} survives those. A caller that calls {@code release} on anything less than a genuinely
 * permanent removal has misused this interface, not exercised a documented edge case.
 */
public interface VolumeManager {

  VolumeHandle allocate(
      String statefulSetName, int instanceIndex, String volumeName, VolumeRequest request);

  Path hostPath(VolumeHandle handle);

  void release(VolumeHandle handle);

  /**
   * Every volume directory currently on disk, including retained orphans whose instance was
   * permanently removed -- the operator-facing inventory behind {@code gimle volume list}.
   */
  List<AllocatedVolume> listAllocated();

  /**
   * Unconditionally deletes every named volume directory under {@code (statefulSetName,
   * instanceIndex)}, ignoring any reclaim policy -- the explicit operator action that reclaims a
   * retained orphan, never called by the platform's own lifecycle (which goes through {@link
   * #release}). A directory that's already gone is a silent no-op, matching {@link #release}'s
   * idempotent posture.
   */
  void destroy(String statefulSetName, int instanceIndex);

  /**
   * The bytes currently occupied across every named volume of {@code (statefulSetName,
   * instanceIndex)}, or 0 with no directory on disk -- the soft usage observation heartbeats
   * sample, never an enforced ceiling.
   */
  long usedBytes(String statefulSetName, int instanceIndex);
}
