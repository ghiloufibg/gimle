package com.gimle.mavenplugin;

import java.util.Optional;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code mvn gimle:ivaldi-stop} -- best-effort shutdown of the local Ivaldi server: asks it to stop
 * over its own {@code POST /api/shutdown} first, and only if that's unreachable (or the server
 * predates the endpoint) falls back to signalling the pid {@code gimle:ivaldi} recorded at spawn
 * time. Never fails the build -- "nothing was running" is a fine outcome for a stop command.
 * Mirrors {@link SagaStopMojo} exactly.
 */
@Mojo(name = "ivaldi-stop", threadSafe = true)
public final class IvaldiStopMojo extends AbstractGimleRootMojo {

  @Parameter(property = "gimle.ivaldi.port", defaultValue = "9097")
  private String port;

  @Override
  protected void executeAtRoot() {
    IvaldiClient client = new IvaldiClient("http://127.0.0.1:" + port);
    if (client.shutdown()) {
      getLog().info("asked the Ivaldi server at " + client.endpoint() + " to shut down");
      IvaldiServer.deletePidFile();
      return;
    }
    Optional<Long> pid = IvaldiServer.recordedPid();
    if (pid.isEmpty()) {
      getLog()
          .info(
              "no Ivaldi server responding at "
                  + client.endpoint()
                  + " and no pidfile at "
                  + IvaldiServer.pidFile()
                  + "; nothing to stop");
      return;
    }
    boolean signalled = ProcessHandle.of(pid.get()).map(ProcessHandle::destroy).orElse(false);
    if (signalled) {
      getLog().info("sent termination signal to Ivaldi server pid " + pid.get());
    } else {
      getLog().info("recorded Ivaldi server pid " + pid.get() + " is not running");
    }
    IvaldiServer.deletePidFile();
  }
}
