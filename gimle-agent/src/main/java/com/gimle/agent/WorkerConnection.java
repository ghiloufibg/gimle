package com.gimle.agent;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * One accepted worker's half of the control channel from the agent's side. The agent listens; each
 * spawned worker connects out and gets its own {@link WorkerConnection}. IO shape mirrors {@code
 * gimle-worker}'s {@code ControlChannelClient} exactly (same newline-framed, space-separated {@link
 * ControlMessageCodec} wire format) -- deliberately not shared as a common abstraction, since the
 * two sides differ in only one respect (who initiates the connection) and factoring that out would
 * cost more than the few lines it saves.
 */
public final class WorkerConnection implements AutoCloseable {

  private final SocketChannel channel;
  private final BufferedReader in;
  private final Writer out;

  WorkerConnection(SocketChannel channel) {
    this.channel = channel;
    this.in =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    this.out = new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
  }

  /**
   * Synchronized: the agent's per-instance startup sequence ({@code driveInstanceUp}) and its
   * per-instance reader loop (relaying catalog updates it learns concurrently) both send on this
   * same connection from different threads -- without this, two concurrent writes could interleave
   * mid-line and corrupt the wire protocol for both.
   */
  public synchronized void send(ControlMessage message) throws IOException {
    out.write(ControlMessageCodec.encode(message));
    out.write("\n");
    out.flush();
  }

  public Optional<ControlMessage> receive() throws IOException {
    String line = in.readLine();
    return line == null ? Optional.empty() : Optional.of(ControlMessageCodec.decode(line));
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
