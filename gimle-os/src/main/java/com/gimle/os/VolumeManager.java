package com.gimle.os;

import com.gimle.core.module.VolumeRequest;
import java.nio.file.Path;

/**
 * Allocates a {@code StatefulSet}-shaped instance's persistent local-disk storage (priority-3
 * design doc §5a), structurally parallel to {@link ResourceLimiter}: one implementation today
 * ({@code LocalDiskVolumeManager}), local-disk-backed only -- no replication, no CSI-style
 * pluggable backend, matching the roadmap's own "single-node-local-disk version of persistent
 * storage" framing (see §5b, and this design's own explicit non-goals).
 *
 * <p>{@code release} is called only on permanent removal (a {@code StatefulSet} index scaled down
 * for good, or the whole spec deleted) -- never on an ordinary reschedule or rolling-update
 * teardown-then-replace, since the entire point of sticky placement is that the data at {@code
 * hostPath} survives those. A caller that calls {@code release} on anything less than a genuinely
 * permanent removal has misused this interface, not exercised a documented edge case.
 */
public interface VolumeManager {

  VolumeHandle allocate(String statefulSetName, int instanceIndex, VolumeRequest request);

  Path hostPath(VolumeHandle handle);

  void release(VolumeHandle handle);
}
