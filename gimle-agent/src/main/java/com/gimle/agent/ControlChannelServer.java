package com.gimle.agent;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The agent's listening end of the control channel: a Unix domain socket bound at a filesystem
 * path, portable across Linux/macOS/Windows 10 1803+ ({@code java.net.UnixDomainSocketAddress} is a
 * JDK API, not an OS-specific mechanism this project branches on). The agent binds once and accepts
 * one connection per spawned worker; direction is deliberately worker-connects-out (see {@code
 * ControlChannelClient}'s javadoc) so there's no agent-not-ready-yet race for a caller to solve.
 */
public final class ControlChannelServer implements AutoCloseable {

  private final Path socketPath;
  private final ServerSocketChannel serverChannel;

  public ControlChannelServer(Path socketPath) throws IOException {
    Files.deleteIfExists(socketPath); // stale socket file left behind by a previous run
    this.socketPath = socketPath;
    this.serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
  }

  public Path socketPath() {
    return socketPath;
  }

  /** Blocks until a worker connects. */
  public WorkerConnection accept() throws IOException {
    return new WorkerConnection(serverChannel.accept());
  }

  @Override
  public void close() throws IOException {
    serverChannel.close();
    Files.deleteIfExists(socketPath);
    // The socket file's parent is a dedicated temp directory created solely to hold it
    // (Files.createTempDirectory("gimle-worker-uds-")) -- empty by the time we get here, so it's
    // safe to remove too rather than leaking one directory per worker start. getParent() is null
    // only for a root path, which socketPath (always inside that temp directory) never is.
    Path parent = socketPath.getParent();
    if (parent != null) {
      Files.deleteIfExists(parent);
    }
  }
}
