package com.gimle.ragnarok.target;

import com.gimle.testkit.heimdall.HeimdallScope;
import java.util.List;
import java.util.Optional;

/**
 * Everything Fenrir and Surtr need from a cluster, independent of how that cluster was booted or
 * who controls it. A harness-owned cluster (a real, in-JVM-spawned process tree) can implement
 * every method meaningfully; a cluster this tool only has network access to can only implement part
 * of it -- and says so honestly through {@link Optional} rather than throwing.
 *
 * <p>The process-handle accessors ({@link #store}, {@link #storeLeader}, {@link #controlPlane},
 * {@link #fafnir}, {@link #muninn}, {@link #andvari}) return {@link Optional} because only a target
 * with real process control can offer one -- an HTTP-only target has no OS-level handle to hand
 * back, and returning empty rather than throwing lets Fenrir record the fault as skipped with a
 * reason instead of crashing the soak. Every other accessor here is already
 * Optional-or-empty-collection-shaped for the same reason.
 *
 * <p>Extends {@link AutoCloseable} (narrowed to no checked exception) so a CLI command can build
 * one from a target document and use it in a single try-with-resources, whichever concrete
 * implementation the document resolved to.
 */
public interface ClusterTarget extends AutoCloseable {

  List<String> controlPlaneBaseUrls();

  int controlPlaneCount();

  /** The control-plane API client for replica 0. */
  ControlPlaneClient api();

  ControlPlaneClient api(int controlPlaneIndex);

  /** Conditions completed by a satisfying view observed through any control-plane replica. */
  HeimdallScope when();

  HeimdallScope when(int controlPlaneIndex);

  Optional<String> storeLeaderId();

  List<String> storeMemberIds();

  int storeCount();

  Optional<GimleProcess> store(int index);

  Optional<GimleProcess> storeLeader();

  Optional<GimleProcess> controlPlane(int index);

  int fafnirCount();

  Optional<GimleProcess> fafnir(int index);

  int muninnCount();

  Optional<GimleProcess> muninn(int index);

  /** True while Muninn replica {@code index} answers its own {@code /status}. */
  boolean muninnServing(int index);

  int andvariCount();

  Optional<GimleProcess> andvari(int index);

  /** True while Andvari replica {@code index} answers its own {@code /status}. */
  boolean andvariServing(int index);

  /** The live worker process currently hosting one instance, if this target can see it at all. */
  Optional<WorkerHandle> workerFor(String deploymentName, int instanceIndex);

  /**
   * The network-fault injector over this target's topology, present only when the target has
   * boot-time interposition over its own links -- a cluster this tool only reaches over the network
   * can never offer this, however capable it is otherwise.
   */
  Optional<NetworkFaultInjector> faults();

  @Override
  void close();
}
