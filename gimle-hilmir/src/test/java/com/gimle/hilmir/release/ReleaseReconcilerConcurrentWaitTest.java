package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@code ReleaseReconciler.awaitIfRequested}: a Job that reaches its own
 * terminal state quickly must be reported as soon as it does, not only once every workload ahead of
 * it in the bundle has finished its own wait. Uses {@link ReleaseReconciler#deployFresh} directly
 * (public in this package) rather than going through {@link DeployCommand}, since the behavior
 * under test lives entirely in the private {@code awaitIfRequested} it calls.
 */
class ReleaseReconcilerConcurrentWaitTest {

  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  /**
   * Records the elapsed time (from construction) at which a line containing {@code marker} first
   * prints.
   */
  private static final class MarkerTimingStream extends PrintStream {
    private final String marker;
    private final long startNanos = System.nanoTime();
    private final AtomicLong firstSeenNanos = new AtomicLong(-1);

    MarkerTimingStream(ByteArrayOutputStream buffer, String marker) {
      super(buffer, true, StandardCharsets.UTF_8);
      this.marker = marker;
    }

    @Override
    public void println(String line) {
      if (line != null && line.contains(marker)) {
        firstSeenNanos.compareAndSet(-1, System.nanoTime());
      }
      super.println(line);
    }

    /**
     * Millis from construction to the first line containing {@code marker}, or -1 if never seen.
     */
    long elapsedMillisToMarker() {
      long seen = firstSeenNanos.get();
      return seen < 0 ? -1 : (seen - startNanos) / 1_000_000;
    }
  }

  private static RenderedBundle bundleOf(RenderedWorkload... workloads) {
    return new RenderedBundle(
        "wait-order-suite", "1.0.0", List.of(), List.of(), List.of(), List.of(workloads));
  }

  /**
   * The exact shape reported live in QA: a slow/never-ready Deployment placed ahead of a
   * fast-succeeding Job. Under the pre-fix sequential loop, the Deployment's own timeout exception
   * aborts the loop before the Job's wait is ever even started, so "succeeded" never appears in the
   * output at all; concurrently, the Job's own success prints almost immediately while the
   * Deployment is still polling toward its timeout in the background.
   */
  @Test
  void a_fast_job_is_reported_ready_without_waiting_on_a_stuck_deployment_ahead_of_it()
      throws Exception {
    fake = new FakeControlPlane();
    fake.setJobPhase("Job", "fast-job", "SUCCEEDED");
    // Deployment "stuck-app" is left with no instances -- never ready.

    RenderedBundle rendered =
        bundleOf(
            new RenderedWorkload("Deployment", "stuck-app", "kind: Deployment\nname: stuck-app\n"),
            new RenderedWorkload("Job", "fast-job", "kind: Job\nname: fast-job\n"));

    String previousTimeout = System.getProperty(WaitPoller.TIMEOUT_PROPERTY);
    System.setProperty(WaitPoller.TIMEOUT_PROPERTY, "50");
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      MarkerTimingStream out = new MarkerTimingStream(buffer, "succeeded");
      ControlPlaneApi api = new ControlPlaneApi(fake.address());

      long startNanos = System.nanoTime();
      HilmirException e =
          assertThrows(
              HilmirException.class, () -> ReleaseReconciler.deployFresh(api, rendered, true, out));
      long totalMillis = (System.nanoTime() - startNanos) / 1_000_000;

      assertTrue(e.getMessage().contains("stuck-app"), e.getMessage());
      String output = buffer.toString(StandardCharsets.UTF_8);
      assertTrue(output.contains("fast-job") && output.contains("succeeded"), output);

      long jobReportedAtMillis = out.elapsedMillisToMarker();
      assertTrue(jobReportedAtMillis >= 0, "the job's own success line never printed");
      // The stuck deployment's own wait takes at least one full poll interval (multi-second) before
      // it times out; the job -- already SUCCEEDED on its very first poll -- must be reported long
      // before that, not only once the deployment's own wait has already given up.
      assertTrue(
          jobReportedAtMillis < totalMillis - 500,
          "job reported at "
              + jobReportedAtMillis
              + "ms but the whole wait only took "
              + totalMillis
              + "ms -- expected the job to be reported well before the deployment's own timeout");
    } finally {
      restoreTimeout(previousTimeout);
    }
  }

  @Test
  void failures_from_every_failed_workload_are_named_in_one_exception() throws Exception {
    fake = new FakeControlPlane();
    // Both deployments are left with no instances -- neither ever becomes ready.

    RenderedBundle rendered =
        bundleOf(
            new RenderedWorkload("Deployment", "stuck-one", "kind: Deployment\nname: stuck-one\n"),
            new RenderedWorkload("Deployment", "stuck-two", "kind: Deployment\nname: stuck-two\n"));

    String previousTimeout = System.getProperty(WaitPoller.TIMEOUT_PROPERTY);
    System.setProperty(WaitPoller.TIMEOUT_PROPERTY, "50");
    try {
      ControlPlaneApi api = new ControlPlaneApi(fake.address());
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

      HilmirException e =
          assertThrows(
              HilmirException.class, () -> ReleaseReconciler.deployFresh(api, rendered, true, out));

      assertTrue(e.getMessage().contains("stuck-one"), e.getMessage());
      assertTrue(e.getMessage().contains("stuck-two"), e.getMessage());
    } finally {
      restoreTimeout(previousTimeout);
    }
  }

  private static void restoreTimeout(String previousTimeout) {
    if (previousTimeout == null) {
      System.clearProperty(WaitPoller.TIMEOUT_PROPERTY);
    } else {
      System.setProperty(WaitPoller.TIMEOUT_PROPERTY, previousTimeout);
    }
  }
}
