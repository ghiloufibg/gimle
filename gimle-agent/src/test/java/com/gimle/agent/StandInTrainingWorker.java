package com.gimle.agent;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * A stand-in for the worker JVM {@link SleipnirTrainer} spawns to fill the AOT cache, launched as a
 * real child process by {@link SleipnirTrainingRunTest} so the trainer's spawn/handshake/shutdown
 * mechanics can be exercised without an AOT-eligible worker classpath.
 *
 * <p>It reproduces the two behaviours of a real worker that decide whether training works at all.
 * It reports {@code Hello} once it has "booted", and then:
 *
 * <ul>
 *   <li>by default it waits for its control channel to close and only then ends, writing the cache
 *       file on its way out -- the JVM writes a real AOT cache from the shutdown path of a process
 *       that ends of its own accord, so nothing appears unless the worker gets there;
 *   <li>with {@code -Dgimle.worker.aotTraining=true} it returns from its handshake and parks
 *       forever instead, as a real worker's JVM does on that path: a worker keeps non-daemon
 *       threads of its own running, so returning from {@code main} never ends the process, and a
 *       cache is never written.
 * </ul>
 *
 * <p>Arguments are {@code <cacheFile> <socketPath>}: the trainer appends the control-socket path to
 * whatever command it was given, the same way it does for a real worker.
 */
public final class StandInTrainingWorker {

  private StandInTrainingWorker() {}

  public static void main(String[] args) throws Exception {
    Path cacheFile = Path.of(args[0]);
    Path socketPath = Path.of(args[1]);

    try (SocketChannel channel =
        SocketChannel.open(UnixDomainSocketAddress.of(socketPath.toString()))) {
      Writer out =
          new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
      out.write(
          ControlMessageCodec.encode(
              new ControlMessage.Hello("stand-in", ProcessHandle.current().pid())));
      out.write("\n");
      out.flush();

      if (Boolean.getBoolean("gimle.worker.aotTraining")) {
        new CountDownLatch(1).await();
      }
      awaitChannelClose(channel);
    }
    writeCacheFile(cacheFile);
  }

  private static void awaitChannelClose(SocketChannel channel) throws Exception {
    ByteBuffer buffer = ByteBuffer.allocate(256);
    while (channel.read(buffer) >= 0) {
      buffer.clear();
    }
  }

  private static void writeCacheFile(Path cacheFile) {
    try {
      Files.writeString(cacheFile, "stand-in-aot-cache-bytes");
    } catch (Exception ignored) {
      // The missing cache file is the signal here, and the trainer reports that itself.
    }
  }
}
