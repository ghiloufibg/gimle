package com.gimle.cli.seam;

import com.gimle.cli.spi.CliExtension;
import com.gimle.cli.spi.ClusterReader;
import java.io.PrintStream;
import java.util.List;

/**
 * A provider declared through {@code src/test/resources/META-INF/services/}, so this module's own
 * suite discovers it the same way the shipped CLI discovers a real one: from the classpath, as an
 * unnamed module. A provider declared only through a {@code module-info} {@code provides} directive
 * would find nothing here, which is the point of testing it this way round.
 */
public final class EchoCliExtension implements CliExtension {

  @Override
  public String verb() {
    return "seam-echo";
  }

  @Override
  public String usageLine() {
    return "seam-echo [args...]";
  }

  @Override
  public void run(final List<String> args, final ClusterReader reader, final PrintStream out) {
    out.println("seam-echo server=" + reader.serverAddress() + " args=" + String.join(",", args));
  }
}
