package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import com.gimle.hilmir.remote.SshProcessExec;
import com.gimle.hilmir.topology.Machine;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.ControlPlaneClient;
import com.gimle.ragnarok.target.GimleProcess;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.WorkerHandle;
import com.gimle.ragnarok.target.endpoint.HttpControlPlaneClient;
import com.gimle.testkit.heimdall.Heimdall;
import com.gimle.testkit.heimdall.HeimdallScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link ClusterTarget} reaching a real, already-running cluster the same network way {@link
 * com.gimle.ragnarok.target.endpoint.EndpointClusterTarget} does (HTTP for the control plane, a
 * direct {@link StoreClient} for the store's own status RPC) -- but with real process control on
 * top, over SSH, resolved from an {@link InventorySpec}. Every network-facing method is identical
 * in behavior to the endpoint target; process control is the only thing this target adds.
 */
public final class SshInventoryClusterTarget implements ClusterTarget {

  private final List<String> controlPlaneBaseUrls;
  private final HttpClient httpClient;
  private final List<SocketAddress> storeClientEndpoints;
  private final List<String> muninnBaseUrls;
  private final List<String> andvariBaseUrls;
  private final Path workDir;
  private final Path knownHostsFile;
  private final InventorySpec inventory;
  private final RemoteExec remoteExec;
  private final Set<String> pinnedMachines = ConcurrentHashMap.newKeySet();
  private final List<SshManagedProcess> spawnedProcesses = new CopyOnWriteArrayList<>();
  private Heimdall heimdall;

  public SshInventoryClusterTarget(
      final List<String> controlPlaneBaseUrls,
      final HttpClient httpClient,
      final List<SocketAddress> storeClientEndpoints,
      final List<String> muninnBaseUrls,
      final List<String> andvariBaseUrls,
      final Path workDir,
      final InventorySpec inventory) {
    this.controlPlaneBaseUrls = List.copyOf(controlPlaneBaseUrls);
    this.httpClient = httpClient;
    this.storeClientEndpoints = List.copyOf(storeClientEndpoints);
    this.muninnBaseUrls = List.copyOf(muninnBaseUrls);
    this.andvariBaseUrls = List.copyOf(andvariBaseUrls);
    this.workDir = workDir;
    this.knownHostsFile = workDir.resolve("known_hosts");
    this.inventory = inventory;
    this.remoteExec = new SshProcessExec(knownHostsFile);
  }

  // ---- network-facing methods, identical to EndpointClusterTarget ----

  @Override
  public List<String> controlPlaneBaseUrls() {
    return controlPlaneBaseUrls;
  }

  @Override
  public int controlPlaneCount() {
    return controlPlaneBaseUrls.size();
  }

  @Override
  public ControlPlaneClient api() {
    return api(0);
  }

  @Override
  public ControlPlaneClient api(final int controlPlaneIndex) {
    return new HttpControlPlaneClient(httpClient, controlPlaneBaseUrls.get(controlPlaneIndex));
  }

  @Override
  public HeimdallScope when() {
    return heimdall().scope(OptionalInt.empty());
  }

  @Override
  public HeimdallScope when(final int controlPlaneIndex) {
    return heimdall().scope(OptionalInt.of(controlPlaneIndex));
  }

  @Override
  public Optional<String> storeLeaderId() {
    if (storeClientEndpoints.isEmpty()) {
      return Optional.empty();
    }
    try (StoreClient client = new StoreClient(storeClientEndpoints)) {
      final String leaderId = client.status().leaderId();
      return leaderId.isEmpty() ? Optional.empty() : Optional.of(leaderId);
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<String> storeMemberIds() {
    if (storeClientEndpoints.isEmpty()) {
      return List.of();
    }
    try (StoreClient client = new StoreClient(storeClientEndpoints)) {
      return client.status().memberIds();
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  // ---- process-control methods, resolved from the inventory over SSH ----

  @Override
  public int storeCount() {
    return inventory.store().size();
  }

  @Override
  public Optional<GimleProcess> store(final int index) {
    return processFor(inventory.store(), index);
  }

  @Override
  public Optional<GimleProcess> storeLeader() {
    final Optional<String> leaderId = storeLeaderId();
    if (leaderId.isEmpty()) {
      return Optional.empty();
    }
    return inventory.store().stream()
        .filter(role -> role.id().equals(leaderId.get()))
        .findFirst()
        .map(this::processFor);
  }

  @Override
  public Optional<GimleProcess> controlPlane(final int index) {
    return processFor(inventory.controlPlane(), index);
  }

  @Override
  public int fafnirCount() {
    return inventory.fafnir().size();
  }

  @Override
  public Optional<GimleProcess> fafnir(final int index) {
    return processFor(inventory.fafnir(), index);
  }

  @Override
  public int muninnCount() {
    return inventory.muninn().size();
  }

  @Override
  public Optional<GimleProcess> muninn(final int index) {
    return processFor(inventory.muninn(), index);
  }

  @Override
  public boolean muninnServing(final int index) {
    return httpStatusOk(muninnBaseUrls.get(index) + "/status");
  }

  @Override
  public int andvariCount() {
    return inventory.andvari().size();
  }

  @Override
  public Optional<GimleProcess> andvari(final int index) {
    return processFor(inventory.andvari(), index);
  }

  @Override
  public boolean andvariServing(final int index) {
    return httpStatusOk(andvariBaseUrls.get(index) + "/status");
  }

  @Override
  public Optional<WorkerHandle> workerFor(final String deploymentName, final int instanceIndex) {
    final Optional<String> nodeId =
        api().placements(deploymentName).stream()
            .filter(p -> p.instanceIndex() == instanceIndex)
            .map(ControlPlaneClient.InstancePlacement::nodeId)
            .findFirst();
    if (nodeId.isEmpty()) {
      return Optional.empty();
    }
    final Optional<AgentSpec> agent = inventory.agentFor(nodeId.get());
    if (agent.isEmpty()) {
      // No inventory entry for this node -- honest absence, same as every other accessor here.
      return Optional.empty();
    }
    final Machine machine = inventory.machineNamed(agent.get().machine()).orElseThrow();
    ensurePinned(machine);
    final ResolvedSshTarget resolvedTarget = inventory.resolvedTarget(agent.get());
    return resolveWorkerPid(resolvedTarget, agent.get(), deploymentName, instanceIndex)
        .map(pid -> new SshWorkerHandle(remoteExec, resolvedTarget, pid));
  }

  /**
   * Greps the remote agent's own platform log for the last {@code "spawned worker <key> as pid
   * <pid>"} line {@code WorkerProcessSupervisor} already writes -- the only place a worker's real
   * OS pid is ever recorded, anywhere; the last match wins since a respawn re-logs with a new pid.
   */
  private Optional<Long> resolveWorkerPid(
      final ResolvedSshTarget target,
      final AgentSpec agent,
      final String deploymentName,
      final int instanceIndex) {
    final String key = deploymentName + "#" + instanceIndex;
    final String logFile = agent.logRoot().resolve("agent-platform.log").toString();
    final String script =
        "grep -o "
            + shellQuote("spawned worker " + key + " as pid [0-9]*")
            + " "
            + shellQuote(logFile)
            + " 2>/dev/null | tail -1";
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      remoteExec.execRaw(target, List.of("sh", "-c", script), capture);
    } catch (final HilmirException e) {
      throw new RagnarokException("SSH command failed against " + target.machineName(), e);
    }
    final String prefix = "[" + target.machineName() + "] ";
    String lastLine = null;
    for (final String line : buffer.toString(StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        lastLine = line.startsWith(prefix) ? line.substring(prefix.length()) : line;
      }
    }
    if (lastLine == null) {
      return Optional.empty();
    }
    final int lastSpace = lastLine.lastIndexOf(' ');
    if (lastSpace < 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(lastLine.substring(lastSpace + 1).trim()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** POSIX single-quoting: close the quote, escape a literal quote, reopen it. */
  private static String shellQuote(final String token) {
    return "'" + token.replace("'", "'\\''") + "'";
  }

  @Override
  public Optional<NetworkFaultInjector> faults() {
    // No boot-time interposition here either -- an inventory target gains process control, not
    // network-fault injection; link-cut/store-partition still always skip.
    return Optional.empty();
  }

  @Override
  public void close() {
    spawnedProcesses.forEach(SshManagedProcess::close);
    if (heimdall != null) {
      heimdall.close();
      heimdall = null;
    }
  }

  private Optional<GimleProcess> processFor(final List<ManagedRoleSpec> roles, final int index) {
    if (index < 0 || index >= roles.size()) {
      return Optional.empty();
    }
    return Optional.of(processFor(roles.get(index)));
  }

  private GimleProcess processFor(final ManagedRoleSpec role) {
    final Machine machine =
        inventory
            .machineNamed(role.machine())
            .orElseThrow(
                () ->
                    new RagnarokException(
                        "managed role "
                            + role.id()
                            + " references unknown machine '"
                            + role.machine()
                            + "'"));
    ensurePinned(machine);
    final ResolvedSshTarget resolvedTarget = inventory.resolvedTarget(role);
    final SshManagedProcess process =
        new SshManagedProcess(remoteExec, resolvedTarget, role, machine.host());
    spawnedProcesses.add(process);
    return process;
  }

  private void ensurePinned(final Machine machine) {
    if (!pinnedMachines.add(machine.name())) {
      return;
    }
    remoteExec.pinHostKey(inventory.resolvedTarget(machine), knownHostsFile);
  }

  private synchronized Heimdall heimdall() {
    if (heimdall == null) {
      heimdall = Heimdall.attach(controlPlaneBaseUrls, List.of(), workDir, httpClient);
    }
    return heimdall;
  }

  private boolean httpStatusOk(final String url) {
    try {
      final HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (final IOException e) {
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
