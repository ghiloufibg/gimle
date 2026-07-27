package com.gimle.agent;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.AssignedInstance;
import java.net.InetSocketAddress;

/**
 * Everything the agent tracks for one instance the control plane has assigned to this node: the
 * work order itself, the worker JVM supervising it, that worker's control-channel listener, and
 * (once the spawned worker connects) the connection used to drive it up and read its reported
 * lifecycle state back. {@code lifecycleState} is updated from {@code ModuleStateChanged} messages
 * as they arrive; {@code connection} starts {@code null} and is filled in asynchronously once the
 * spawned worker JVM actually connects, so creating this instance never blocks the assignment-poll
 * loop on a slow-starting JVM.
 *
 * <p>{@code fabricWorkerId}/{@code fabricUdsPath}/{@code fabricTcpAddress} are populated from the
 * worker's {@code Hello} handshake -- the addressing needed to advertise a dialable {@link
 * com.gimle.fabric.catalog.ServiceEndpoint} once this instance later reports a {@code
 * ServiceRegistered}.
 */
final class SupervisedInstance {

  final AssignedInstance assigned;
  final WorkerProcessSupervisor supervisor;
  final ControlChannelServer server;
  final ModuleDescriptor descriptor;

  volatile WorkerConnection connection;
  volatile String lifecycleState = "INSTALLED";
  volatile String fabricWorkerId;
  volatile String fabricUdsPath = "";
  volatile InetSocketAddress fabricTcpAddress;

  SupervisedInstance(
      AssignedInstance assigned,
      WorkerProcessSupervisor supervisor,
      ControlChannelServer server,
      ModuleDescriptor descriptor) {
    this.assigned = assigned;
    this.supervisor = supervisor;
    this.server = server;
    this.descriptor = descriptor;
  }
}
