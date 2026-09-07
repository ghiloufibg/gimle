package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@code ReleaseReconciler.awaitAll}: interrupting the run-worker thread
 * that called {@link ReleaseReconciler#deployFresh} (the same signal a Runner "Stop" delivers) must
 * actually end a stuck {@code --wait} promptly, not only once the per-workload poll's own (much
 * longer) timeout eventually fires on its own.
 */
class ReleaseReconcilerCancellationTest {

  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static RenderedBundle bundleOf(RenderedWorkload... workloads) {
    return new RenderedBundle(
        "cancel-suite", "1.0.0", List.of(), List.of(), List.of(), List.of(workloads));
  }

  @Test
  void interrupting_the_calling_thread_ends_a_stuck_wait_promptly_instead_of_running_out_the_clock()
      throws Exception {
    fake = new FakeControlPlane();
    // Deployment "stuck-app" is left with no instances -- never ready on its own. The timeout is
    // set far longer than this test's own patience so only a delivered interrupt, not the
    // poll's own deadline, can end the wait within the assertion window below.
    RenderedBundle rendered =
        bundleOf(
            new RenderedWorkload("Deployment", "stuck-app", "kind: Deployment\nname: stuck-app\n"));

    String previousTimeout = System.getProperty(WaitPoller.TIMEOUT_PROPERTY);
    System.setProperty(WaitPoller.TIMEOUT_PROPERTY, "60000");
    try {
      ControlPlaneApi api = new ControlPlaneApi(fake.address());
      PrintStream out = new PrintStream(OutputStream.nullOutputStream());

      AtomicReference<Throwable> thrown = new AtomicReference<>();
      CountDownLatch started = new CountDownLatch(1);
      Thread runWorker =
          new Thread(
              () -> {
                started.countDown();
                try {
                  ReleaseReconciler.deployFresh(api, rendered, true, out);
                } catch (Throwable t) {
                  thrown.set(t);
                }
              });
      runWorker.start();
      started.await();
      // Give the wait time to actually enter its poll loop before being cancelled.
      Thread.sleep(100);

      long interruptedAtNanos = System.nanoTime();
      runWorker.interrupt();
      runWorker.join(2_000);
      long elapsedMillis = (System.nanoTime() - interruptedAtNanos) / 1_000_000;

      assertFalse(runWorker.isAlive(), "run-worker thread did not stop after being interrupted");
      assertTrue(
          elapsedMillis < 500,
          "interrupt took "
              + elapsedMillis
              + "ms to take effect -- expected the stuck wait to end promptly, well under the"
              + " 60s configured timeout");
      assertNotNull(thrown.get(), "expected the cancelled wait to surface as a failure");
      assertTrue(
          thrown.get() instanceof HilmirException,
          "expected a HilmirException, got " + thrown.get());
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
