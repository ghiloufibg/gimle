package com.gimle.ragnarok.target.inventory;

import com.gimle.ragnarok.RagnarokException;
import java.nio.file.Path;
import java.util.List;

/**
 * One managed-inventory process: which {@link com.gimle.hilmir.topology.Machine} it lives on, its
 * stable identity ({@code store-0}, {@code controlplane-1}, ...), and everything an {@link
 * SshManagedProcess} needs to find and control it -- deliberately self-sufficient rather than
 * parsed from {@code gimle-hilmir}'s own {@code hilmir-run.json} ledger, since Ragnarök must work
 * against a process it didn't spawn and that ledger is hilmir's own private bookkeeping for
 * processes it did.
 */
public record ManagedRoleSpec(
    String machine, String id, Path pidFile, Path logFile, List<String> command) {

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
  }
}
