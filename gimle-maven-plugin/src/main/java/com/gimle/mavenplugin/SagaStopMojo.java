package com.gimle.mavenplugin;

import java.util.Optional;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code mvn gimle:saga-stop} -- best-effort shutdown of the local Saga server: asks it to stop
 * over its own {@code POST /api/shutdown} first, and only if that's unreachable (or the server
 * predates the endpoint) falls back to signalling the pid {@code gimle:saga} recorded at spawn
 * time. Never fails the build -- "nothing was running" is a fine outcome for a stop command.
 */
@Mojo(name = "saga-stop", threadSafe = true)
public final class SagaStopMojo extends AbstractGimleRootMojo {

  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  private String port;

  @Override
  protected void executeAtRoot() {
    SagaClient client = new SagaClient("http://127.0.0.1:" + port);
    if (client.shutdown()) {
      getLog().info("asked the Saga server at " + client.endpoint() + " to shut down");
      SagaServer.deletePidFile();
      return;
    }
    Optional<Long> pid = SagaServer.recordedPid();
    if (pid.isEmpty()) {
      getLog()
          .info(
              "no Saga server responding at "
                  + client.endpoint()
                  + " and no pidfile at "
                  + SagaServer.pidFile()
                  + "; nothing to stop");
      return;
    }
    boolean signalled = ProcessHandle.of(pid.get()).map(ProcessHandle::destroy).orElse(false);
    if (signalled) {
      getLog().info("sent termination signal to Saga server pid " + pid.get());
    } else {
      getLog().info("recorded Saga server pid " + pid.get() + " is not running");
    }
    SagaServer.deletePidFile();
  }
}
