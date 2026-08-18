# Gimle OS

The narrow abstraction layer between a module's declared resource/storage requirements and the
worker JVM process that will actually enforce them. Two small interfaces, `ResourceLimiter` and
`VolumeManager`, each with exactly one implementation today, both deliberately platform-independent:
no cgroups, no OS-specific code, no FFM downcalls. `gimle-os` depends only on `gimle-core`, and is
itself depended on only by `gimle-agent`, which is the sole caller — the node agent is what actually
spawns worker JVMs and needs to turn a `ResourceSpec` into JVM flags before doing so.

## `ResourceLimiter`

```java
public interface ResourceLimiter {
  boolean supports(IsolationTier tier);
  ResourceLimitHandle prepare(String workerId, ResourceSpec limit);
  List<String> jvmFlags(ResourceLimitHandle handle);
  void release(ResourceLimitHandle handle);
}
```

`PortableJvmFlagsResourceLimiter` (`com.gimle.os.portable`) is the only implementation, and the
guaranteed minimum every future implementation must at least match: it derives `-Xmx` from
`ResourceSpec.memoryBytes()` and `-XX:ActiveProcessorCount` from `ResourceSpec.cpuMillicores()`,
identically on Linux/macOS/Windows, and `release` is a no-op — there is no cgroup, no filesystem
state, no live resource held. It supports `TIER_1` and `TIER_2` only (`TIER_3` needs a namespace,
which this limiter has nothing to do with).

The interface exists — rather than the agent just calling the portable implementation directly —
because portable JVM-flag enforcement and kernel-level enforcement (cgroup v2 on Linux) are
genuinely different strategies with different guarantees, and both are expected to eventually
coexist. `ResourceLimiter` is weaker than kernel-level enforcement by design: a runaway native
allocation isn't caught, which is exactly what "JVM-level limits" means. A cgroup-backed
implementation is a deliberately deferred second implementation of this same interface, not a
parallel path — every caller here is already written against the interface and never branches on
platform, so it drops in without touching a caller.

## `VolumeManager`

```java
public interface VolumeManager {
  VolumeHandle allocate(String statefulSetName, int instanceIndex, VolumeRequest request);
  Path hostPath(VolumeHandle handle);
  void release(VolumeHandle handle);
}
```

`LocalDiskVolumeManager` (`com.gimle.os.localdisk`) is the only implementation: one directory per
`(statefulSetName, instanceIndex)` pair under `<dataRoot>/volumes/`, free space checked at
`allocate` time only (soft/advisory, the same no-continuous-enforcement posture
`PortableJvmFlagsResourceLimiter` takes, extended from CPU/memory to disk — not a new precedent).
No replication, no CSI-style pluggable backend — single-node local disk is the deliberate scope for
persistent storage here: a sticky-placement `StatefulSet` instance's data lives on the machine it's
scheduled to, and nothing in this interface assumes otherwise. `release` is called only on permanent
removal (a `StatefulSet` index scaled down for good, or the whole spec deleted), never on an
ordinary reschedule or rolling-update teardown-then-replace — the entire point of sticky placement is
that data at `hostPath` survives those.

`LocalDiskVolumeManager#allocate` raises `GimleVolumeException` (`gimle-core`) on directory-creation
failure or insufficient free space, rather than returning an error value — consistent with the rest
of the codebase's no-checked-exceptions convention.
