package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.observability.MuninnShipper;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * M35: a freshly spawned Tier 1/Tier 2 worker's real committed ceiling ({@code handle.limit()})
 * used to never be checked against this node's own real machine memory at all -- {@link
 * AgentMain#startInstance} spawned unconditionally, so nothing ever refused a spawn or warned an
 * operator once accumulated shared-worker ceilings already exceeded what the machine can back.
 * {@code committedWorkerCapacity} is a second {@link CapacityTracker} instance dedicated to that
 * real-ceiling accounting, deliberately separate from the existing {@code capacityTracker} (which
 * tracks each instance's own tiny declared *request*, read elsewhere via its own {@code
 * snapshot()}) -- see {@code AgentMain#main}'s own comment on why the two must never share one
 * running sum.
 *
 * <p>Every scenario here that must actually reach {@code supervisor.start()} spawns a real {@code
 * gimle-worker} subprocess (the same technique {@code Tier1DensityIntegrationTest} already uses)
 * rather than a fake, since the guard under test sits directly in front of that real call. Every
 * test tears its own worker down (via {@link AgentMain#stopInstance}) before returning, whether or
 * not the assertions in the middle already did.
 */
class CommittedWorkerCapacityGuardTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ResourceSpec SMALL_REQUEST = new ResourceSpec("4Mi", "50m");
  private static final ResourceSpec SMALL_LIMIT = new ResourceSpec("40Mi", "200m");

  /**
   * A deliberately tiny {@link Tier1WorkerBudget} (heap-dominated: {@code sizeFor} always returns
   * this flat heap size for {@link #SMALL_LIMIT}, since {@code limit + overhead} stays under it) --
   * every Tier 1 worker spawned in this test carries exactly this ceiling, so the arithmetic below
   * is exact rather than approximate.
   */
  private static final Tier1WorkerBudget TINY_TIER1_BUDGET =
      Tier1WorkerBudget.parse("50Mi", "500m", "8Mi");

  private static final long WORKER_MEMORY_BYTES = new ResourceSpec("50Mi", "500m").memoryBytes();

  private static final String NODE_ID = "test-node";

  private static ModuleDescriptor descriptor(String name) {
    return new ModuleDescriptor(
        name,
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        SMALL_REQUEST,
        SMALL_LIMIT,
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  private static AssignedInstance assignedInstance(String deploymentName, ModuleDescriptor d) {
    return new AssignedInstance(
        deploymentName, 0, d.id(), "/does/not/matter.jar", Optional.empty());
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (java.nio.file.Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }

  private static List<String> workerCommandTail() {
    return List.of("-cp", System.getProperty("java.class.path"), "com.gimle.worker.WorkerMain");
  }

  /**
   * Everything {@link AgentMain#startInstance}/{@link AgentMain#stopInstance} need beyond the
   * per-test {@code committedWorkerCapacity} budget, bundled so each test only has to name what
   * actually varies.
   */
  private final class Fixture {
    final Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    final Map<String, List<MuninnShipper>> instanceShippers = new ConcurrentHashMap<>();
    final Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();
    final PortableJvmFlagsResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    final CapacityTracker capacityTracker = new CapacityTracker(1_000_000_000L, 4000L);
    final SleipnirCache sleipnirCache =
        new SleipnirCache(tempDir.resolve("aot-cache"), supervised, javaExecutable());
    final ServiceCatalog catalog = new ServiceCatalog();
    final HttpClient httpClient = HttpClient.newHttpClient();
    final URI baseUrl = URI.create("http://127.0.0.1:1");
    final LocalDiskVolumeManager volumeManager =
        new LocalDiskVolumeManager(tempDir.resolve("volumes"));

    void start(
        AssignedInstance assigned,
        String key,
        ModuleDescriptor d,
        CapacityTracker committedWorkerCapacity)
        throws IOException {
      AgentMain.startInstance(
          assigned,
          key,
          d,
          supervised,
          NODE_ID,
          javaExecutable(),
          workerCommandTail(),
          resourceLimiter,
          TINY_TIER1_BUDGET,
          sleipnirCache,
          capacityTracker,
          committedWorkerCapacity,
          null,
          catalog,
          httpClient,
          baseUrl,
          baseUrl,
          null,
          instanceShippers,
          workerShippers,
          tempDir,
          volumeManager);
    }

    void stop(String key, CapacityTracker committedWorkerCapacity) {
      AgentMain.stopInstance(
          key,
          supervised,
          capacityTracker,
          committedWorkerCapacity,
          instanceShippers,
          workerShippers,
          volumeManager,
          true,
          catalog,
          NODE_ID);
    }
  }

  @Test
  void a_spawn_that_would_exceed_the_real_memory_budget_is_refused_and_leaves_no_trace() {
    // Smaller than one worker's own ceiling -- tryAssign must refuse before supervisor.start() is
    // ever reached, so this test spawns nothing and needs no teardown.
    CapacityTracker committedWorkerCapacity = new CapacityTracker(WORKER_MEMORY_BYTES / 2, 10_000L);
    Fixture fixture = new Fixture();
    ModuleDescriptor d = descriptor("refused-module");
    AssignedInstance assigned = assignedInstance("refused-deployment", d);
    String key = "refused-deployment#0";

    assertThrows(IOException.class, () -> fixture.start(assigned, key, d, committedWorkerCapacity));

    assertFalse(fixture.supervised.containsKey(key), "a refused spawn must not leave an entry");
    assertEquals(
        0L,
        committedWorkerCapacity.snapshot().assignedMemoryBytes(),
        "a refused spawn must reserve nothing");
  }

  @Test
  void an_ordinary_spawn_under_budget_commits_its_real_worker_ceiling() throws Exception {
    // Comfortable room for one worker.
    CapacityTracker committedWorkerCapacity =
        new CapacityTracker(WORKER_MEMORY_BYTES * 3 / 2, 10_000L);
    Fixture fixture = new Fixture();
    ModuleDescriptor d = descriptor("ordinary-module");
    AssignedInstance assigned = assignedInstance("ordinary-deployment", d);
    String key = "ordinary-deployment#0";

    try {
      fixture.start(assigned, key, d, committedWorkerCapacity);

      assertEquals(
          WORKER_MEMORY_BYTES,
          committedWorkerCapacity.snapshot().assignedMemoryBytes(),
          "a real spawn must commit its own worker's real ceiling, not its declared request");
      assertTrue(fixture.supervised.containsKey(key));
    } finally {
      fixture.stop(key, committedWorkerCapacity);
    }
  }

  @Test
  void
      stopping_a_worker_releases_its_reservation_so_a_later_spawn_that_would_not_otherwise_fit_succeeds()
          throws Exception {
    // Sized for exactly one worker at a time: a second spawn while the first is still up must be
    // refused, which is what proves the later success below only happens because release actually
    // freed real room, not because there was ever slack for two.
    CapacityTracker committedWorkerCapacity =
        new CapacityTracker(WORKER_MEMORY_BYTES * 3 / 2, 10_000L);
    Fixture fixture = new Fixture();
    ModuleDescriptor firstDescriptor = descriptor("first-module");
    AssignedInstance first = assignedInstance("first-deployment", firstDescriptor);
    String firstKey = "first-deployment#0";
    ModuleDescriptor secondDescriptor = descriptor("second-module");
    AssignedInstance second = assignedInstance("second-deployment", secondDescriptor);
    String secondKey = "second-deployment#0";

    fixture.start(first, firstKey, firstDescriptor, committedWorkerCapacity);
    try {
      assertThrows(
          IOException.class,
          () -> fixture.start(second, secondKey, secondDescriptor, committedWorkerCapacity),
          "a second worker's own ceiling must not fit alongside the first, unreleased one");
      assertFalse(fixture.supervised.containsKey(secondKey));

      fixture.stop(firstKey, committedWorkerCapacity);
      assertEquals(
          0L,
          committedWorkerCapacity.snapshot().assignedMemoryBytes(),
          "stopping the only worker holding a reservation must release it -- a missed release"
              + " here would otherwise leak forever and wrongly refuse every later spawn");

      // Would have failed exactly like the first attempt above had the release above leaked.
      fixture.start(second, secondKey, secondDescriptor, committedWorkerCapacity);
      assertTrue(fixture.supervised.containsKey(secondKey));
    } finally {
      fixture.stop(firstKey, committedWorkerCapacity);
      fixture.stop(secondKey, committedWorkerCapacity);
    }
  }

  @Test
  void packing_a_sibling_into_an_already_running_worker_reserves_no_additional_memory()
      throws Exception {
    // Exactly enough for one worker's ceiling -- if packing the sibling below spuriously reserved
    // a second share, this budget would already prove it insufficient.
    CapacityTracker committedWorkerCapacity = new CapacityTracker(WORKER_MEMORY_BYTES, 10_000L);
    Fixture fixture = new Fixture();
    ModuleDescriptor ownerDescriptor = descriptor("owner-module");
    AssignedInstance owner = assignedInstance("owner-deployment", ownerDescriptor);
    String ownerKey = "owner-deployment#0";
    ModuleDescriptor siblingDescriptor = descriptor("sibling-module");
    AssignedInstance sibling = assignedInstance("sibling-deployment", siblingDescriptor);
    String siblingKey = "sibling-deployment#0";

    fixture.start(owner, ownerKey, ownerDescriptor, committedWorkerCapacity);
    try {
      SupervisedInstance ownerInstance = fixture.supervised.get(ownerKey);

      AgentMain.installIntoExistingWorker(
          sibling,
          siblingKey,
          siblingDescriptor,
          ownerInstance,
          fixture.supervised,
          NODE_ID,
          fixture.capacityTracker,
          fixture.httpClient,
          fixture.baseUrl,
          fixture.baseUrl,
          null,
          fixture.instanceShippers,
          tempDir,
          fixture.volumeManager);

      assertTrue(fixture.supervised.containsKey(siblingKey), "packing must still register the key");
      assertEquals(
          WORKER_MEMORY_BYTES,
          committedWorkerCapacity.snapshot().assignedMemoryBytes(),
          "packing a sibling into an already-running worker must not reserve additional real"
              + " memory -- the worker's own footprint was already committed once, by the spawn"
              + " that started it");
    } finally {
      fixture.supervised.remove(siblingKey);
      fixture.stop(ownerKey, committedWorkerCapacity);
    }
  }
}
