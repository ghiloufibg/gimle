package com.gimle.worker;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Worker-side half of the newline-delimited control channel (design §4.2): the worker always
 * connects <em>out</em> to the agent's listening socket, retrying until the agent's listener is up
 * -- the agent (long-lived) vs. worker (transient, just spawned) startup race is exactly the
 * asymmetry that connection direction is meant to sidestep rather than solve with extra
 * coordination.
 */
final class ControlChannelClient implements AutoCloseable {

  private final SocketChannel channel;
  private final BufferedReader in;
  private final Writer out;

  private ControlChannelClient(SocketChannel channel) {
    this.channel = channel;
    this.in =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    this.out = new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
  }

  static ControlChannelClient connectWithRetry(
      SocketAddress address, Duration retryInterval, Duration timeout) throws IOException {
    Instant deadline = Instant.now().plus(timeout);
    IOException lastFailure = null;
    while (Instant.now().isBefore(deadline)) {
      try {
        return new ControlChannelClient(SocketChannel.open(address));
      } catch (IOException e) {
        lastFailure = e;
        try {
          Thread.sleep(retryInterval.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(
              "interrupted while retrying control channel connection", interrupted);
        }
      }
    }
    throw new IOException(
        "could not connect to agent control socket at " + address + " within " + timeout,
        lastFailure);
  }

  void send(ControlMessage message) throws IOException {
    out.write(ControlMessageCodec.encode(message));
    out.write("\n");
    out.flush();
  }

  Optional<ControlMessage> receive() throws IOException {
    String line = in.readLine();
    return line == null ? Optional.empty() : Optional.of(ControlMessageCodec.decode(line));
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
