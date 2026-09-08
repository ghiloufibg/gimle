package com.gimle.hilmir.launch.fixture;

/**
 * A minimal standalone child process for hilmir's own descendant-kill-escalation tests: parks
 * forever, optionally trapping SIGTERM. Passing {@code true} installs a shutdown hook that blocks
 * forever, so the JVM's own default signal handling -- which runs shutdown hooks before actually
 * halting -- never reaches the point of terminating; this is what a worker JVM that doesn't honor
 * SIGTERM promptly (mid-GC, a hung native call, a large in-flight drain) looks like from the
 * outside. Passing {@code false} installs no hook at all, so the process terminates the moment it
 * is sent SIGTERM, the way a cooperative descendant does.
 */
public final class ChildFixtureMain {

  private ChildFixtureMain() {}

  public static void main(final String[] args) throws InterruptedException {
    if (Boolean.parseBoolean(args[0])) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Thread.sleep(Long.MAX_VALUE);
                    } catch (final InterruptedException ignored) {
                      // Swallowed on purpose: destroyForcibly() (SIGKILL) must still work even if
                      // this hook's own blocking sleep is interrupted mid-shutdown.
                    }
                  }));
    }
    Thread.sleep(Long.MAX_VALUE);
  }
}
