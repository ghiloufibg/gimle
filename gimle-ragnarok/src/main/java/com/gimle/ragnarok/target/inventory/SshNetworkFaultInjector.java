package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import com.gimle.hilmir.topology.Machine;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link NetworkFaultInjector} enforced with real {@code iptables} rules pushed over SSH via
 * {@link RemoteExec} -- the SSH-inventory target's counterpart to {@code gimle-holmgang}'s in-JVM
 * {@code Loki} proxy, for a real external cluster this tool never interposed at boot time.
 *
 * <p>{@link #cutControlPlaneFromStores} needs no inventory field beyond what {@link
 * SshInventoryClusterTarget} already holds (the target document's own {@code storeClientEndpoints})
 * -- one {@code REJECT --reject-with tcp-reset} rule per store, on the control-plane machine's own
 * {@code OUTPUT} chain, matching {@code Fenrir.linkCut}'s "link severed" semantics: an immediate
 * reset rather than a hang until the fault's own pre-cut probe (`!isServing()`) times out. {@link
 * #cutStoreFromPeers} needs every {@code store:} role's own {@code raftPort} (see {@link
 * ManagedRoleSpec}) and installs {@code DROP} rules -- silent, not reset -- on the victim's own
 * {@code OUTPUT}/{@code INPUT} chains against every peer, matching {@code STORE_PARTITION}'s
 * "genuine partition" semantics (mirroring {@code Loki}'s own {@code cut()}-vs-{@code blackhole()}
 * split). Every rule this class inserts is tagged {@code -m comment --comment "ragnarok-fault"}
 * purely for operator forensics if a crash leaves one stuck -- {@link SshFaultPartition#heal()}
 * never depends on that tag, it replays the exact argv it inserted.
 */
final class SshNetworkFaultInjector implements NetworkFaultInjector {

  private static final String COMMENT_TAG = "ragnarok-fault";

  private final RemoteExec remoteExec;
  private final InventorySpec inventory;
  private final boolean sudo;
  private final Consumer<Machine> ensurePin;
  private final List<SocketAddress> storeClientEndpoints;

  SshNetworkFaultInjector(
      final RemoteExec remoteExec,
      final InventorySpec inventory,
      final List<SocketAddress> storeClientEndpoints,
      final Consumer<Machine> ensurePin) {
    this.remoteExec = remoteExec;
    this.inventory = inventory;
    this.sudo = inventory.sudo();
    this.storeClientEndpoints = List.copyOf(storeClientEndpoints);
    this.ensurePin = ensurePin;
  }

  @Override
  public Partition cutControlPlaneFromStores(final int controlPlaneIndex) {
    final ManagedRoleSpec role =
        roleAt(inventory.controlPlane(), controlPlaneIndex, "control plane");
    final ResolvedSshTarget target = resolve(role);
    final List<List<String>> inserted = new ArrayList<>();
    for (final SocketAddress endpoint : storeClientEndpoints) {
      final InetSocketAddress store = (InetSocketAddress) endpoint;
      final List<String> argv =
          iptablesArgv(
              "-I",
              "OUTPUT",
              "-d",
              store.getHostString(),
              "--dport",
              String.valueOf(store.getPort()),
              "REJECT",
              List.of("--reject-with", "tcp-reset"));
      run(target, argv, "cutting control plane " + role.id() + " from store " + store);
      inserted.add(argv);
    }
    return new SshFaultPartition(target, inserted);
  }

  @Override
  public Partition cutStoreFromPeers(final int storeIndex) {
    final ManagedRoleSpec victim = roleAt(inventory.store(), storeIndex, "store");
    final int victimRaftPort =
        victim
            .raftPort()
            .orElseThrow(
                () ->
                    new RagnarokException(
                        "cannot partition store "
                            + victim.id()
                            + ": no raftPort declared for it in the inventory"));
    final ResolvedSshTarget target = resolve(victim);
    final List<List<String>> inserted = new ArrayList<>();
    for (int i = 0; i < inventory.store().size(); i++) {
      if (i == storeIndex) {
        continue;
      }
      final ManagedRoleSpec peer = inventory.store().get(i);
      final int peerRaftPort =
          peer.raftPort()
              .orElseThrow(
                  () ->
                      new RagnarokException(
                          "cannot partition store "
                              + victim.id()
                              + " from peer "
                              + peer.id()
                              + ": no raftPort declared for the peer in the inventory"));
      final String peerHost =
          inventory
              .machineNamed(peer.machine())
              .orElseThrow(
                  () ->
                      new RagnarokException(
                          "store "
                              + peer.id()
                              + " references unknown machine '"
                              + peer.machine()
                              + "'"))
              .host();
      final List<String> outbound =
          iptablesArgv(
              "-I",
              "OUTPUT",
              "-d",
              peerHost,
              "--dport",
              String.valueOf(peerRaftPort),
              "DROP",
              List.of());
      final List<String> inbound =
          iptablesArgv(
              "-I",
              "INPUT",
              "-s",
              peerHost,
              "--dport",
              String.valueOf(victimRaftPort),
              "DROP",
              List.of());
      run(target, outbound, "partitioning store " + victim.id() + " from peer " + peer.id());
      inserted.add(outbound);
      run(target, inbound, "partitioning store " + victim.id() + " from peer " + peer.id());
      inserted.add(inbound);
    }
    return new SshFaultPartition(target, inserted);
  }

  private ManagedRoleSpec roleAt(
      final List<ManagedRoleSpec> roles, final int index, final String label) {
    if (index < 0 || index >= roles.size()) {
      throw new RagnarokException(
          "no " + label + " replica at index " + index + " (declared: " + roles.size() + ")");
    }
    return roles.get(index);
  }

  private ResolvedSshTarget resolve(final ManagedRoleSpec role) {
    final Machine machine =
        inventory
            .machineNamed(role.machine())
            .orElseThrow(
                () ->
                    new RagnarokException(
                        role.id() + " references unknown machine '" + role.machine() + "'"));
    ensurePin.accept(machine);
    return inventory.resolvedTarget(role);
  }

  /** Every {@code -I} rule shares this shape: chain, address match, port match, jump target. */
  private List<String> iptablesArgv(
      final String flag,
      final String chain,
      final String addressFlag,
      final String host,
      final String portFlag,
      final String port,
      final String jump,
      final List<String> jumpExtra) {
    final List<String> argv = new ArrayList<>();
    if (sudo) {
      argv.add("sudo");
      argv.add("-n");
    }
    argv.add("iptables");
    argv.add(flag);
    argv.add(chain);
    argv.add(addressFlag);
    argv.add(host);
    argv.add("-p");
    argv.add("tcp");
    argv.add(portFlag);
    argv.add(port);
    argv.add("-j");
    argv.add(jump);
    argv.addAll(jumpExtra);
    argv.add("-m");
    argv.add("comment");
    argv.add("--comment");
    argv.add(COMMENT_TAG);
    return argv;
  }

  private void run(final ResolvedSshTarget target, final List<String> argv, final String action) {
    final int exitCode;
    try {
      exitCode = remoteExec.execRaw(target, argv, new PrintStream(OutputStream.nullOutputStream()));
    } catch (final HilmirException e) {
      throw new RagnarokException("SSH command failed against " + target.machineName(), e);
    }
    if (exitCode != 0) {
      throw new RagnarokException(
          "failed " + action + " on " + target.machineName() + " (iptables exit " + exitCode + ")");
    }
  }

  /**
   * Replays the exact {@code -I} argv it was built from, each swapped to {@code -D}, tolerant of a
   * rule already being gone (a repeated {@code heal()}, or a machine that rebooted since the fault
   * fired) -- never searches/matches existing rules by the {@code ragnarok-fault} comment tag, only
   * that class's own recorded insert list.
   */
  private final class SshFaultPartition implements Partition {

    private final ResolvedSshTarget target;
    private final List<List<String>> insertedRules;

    SshFaultPartition(final ResolvedSshTarget target, final List<List<String>> insertedRules) {
      this.target = target;
      this.insertedRules = List.copyOf(insertedRules);
    }

    @Override
    public void heal() {
      if (insertedRules.isEmpty()) {
        return;
      }
      final StringBuilder script = new StringBuilder();
      for (final List<String> insertArgv : insertedRules) {
        final List<String> deleteArgv = new ArrayList<>(insertArgv);
        deleteArgv.set(deleteArgv.indexOf("-I"), "-D");
        for (final String token : deleteArgv) {
          script.append(shellQuote(token)).append(' ');
        }
        script.append("2>/dev/null; ");
      }
      script.append("true");
      try {
        remoteExec.execRaw(
            target,
            List.of("sh", "-c", script.toString()),
            new PrintStream(OutputStream.nullOutputStream()));
      } catch (final HilmirException e) {
        // Best-effort, matching every other Partition.heal() in this codebase (Loki's own heal()
        // is unconditional too): a machine that's gone unreachable since the fault fired must not
        // stop Fenrir's own recovery gate from running and reporting the real, honest outcome.
      }
    }
  }

  /** POSIX single-quoting: close the quote, escape a literal quote, reopen it. */
  private static String shellQuote(final String token) {
    return "'" + token.replace("'", "'\\''") + "'";
  }
}
