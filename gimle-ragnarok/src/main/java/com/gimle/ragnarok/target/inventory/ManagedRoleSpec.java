package com.gimle.ragnarok.target.inventory;

import com.gimle.ragnarok.RagnarokException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One managed-inventory process: which {@link com.gimle.hilmir.topology.Machine} it lives on, its
 * stable identity ({@code store-0}, {@code controlplane-1}, ...), and everything an {@link
 * SshManagedProcess} needs to find and control it -- deliberately self-sufficient rather than
 * parsed from {@code gimle-hilmir}'s own {@code hilmir-run.json} ledger, since Ragnarök must work
 * against a process it didn't spawn and that ledger is hilmir's own private bookkeeping for
 * processes it did.
 *
 * <p>{@code raftPort} is meaningful only for a {@code store:} role -- {@link
 * SshNetworkFaultInjector#cutStoreFromPeers} needs every store's own raft listen port to block
 * store-to-store traffic by port; a {@code controlPlane}/{@code fafnir}/{@code muninn}/{@code
 * andvari} role simply leaves it {@link Optional#empty()}, since none of those ever participates in
 * a {@link com.gimle.ragnarok.target.NetworkFaultInjector} call.
 */
public record ManagedRoleSpec(
    String machine,
    String id,
    Path pidFile,
    Path logFile,
    List<String> command,
    Optional<Integer> raftPort) {

  public ManagedRoleSpec {
    if (machine == null || machine.isBlank()) {
      throw new RagnarokException("a managed role must name a non-blank machine");
    }
    if (id == null || id.isBlank()) {
      throw new RagnarokException("a managed role must have a non-blank id");
    }
    if (pidFile == null) {
      throw new RagnarokException("managed role " + id + " must declare a pidFile");
    }
    if (logFile == null) {
      throw new RagnarokException("managed role " + id + " must declare a logFile");
    }
    command = List.copyOf(command);
    if (command.isEmpty()) {
      throw new RagnarokException("managed role " + id + " must declare a non-empty command");
    }
    if (raftPort == null) {
      throw new RagnarokException("managed role " + id + "'s raftPort field must not be null");
    }
    if (raftPort.isPresent() && (raftPort.get() < 1 || raftPort.get() > 65535)) {
      throw new RagnarokException("managed role " + id + "'s raftPort must be 1-65535 if present");
    }
  }
}
