package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.io.OutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@code WaitPoller.awaitJobTerminal}: the control plane's own {@code
 * ApiServer.jobStatus} nests a failed Job's reason under {@code currentRun.reason}, never at the
 * response's top level, so a failure's real cause must actually be read from there to reach the
 * surfaced error.
 */
class WaitPollerTest {

  private FakeControlPlane fake;
  private final PrintStream out = new PrintStream(OutputStream.nullOutputStream());

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  @Test
  void a_failed_jobs_nested_reason_is_included_in_the_surfaced_error() throws Exception {
    fake = new FakeControlPlane();
    fake.setJobPhase("Job", "deadline-job", "FAILED");
    fake.setJobFailureReason(
        "Job", "deadline-job", "exceeded activeDeadline of PT5S across all attempts");
    ControlPlaneApi api = new ControlPlaneApi(fake.address());
    RenderedWorkload workload =
        new RenderedWorkload("Job", "deadline-job", "kind: Job\nname: deadline-job\n");

    HilmirException e =
        assertThrows(HilmirException.class, () -> WaitPoller.awaitReady(api, workload, out));

    assertTrue(
        e.getMessage().contains("exceeded activeDeadline of PT5S across all attempts"),
        e.getMessage());
    assertTrue(e.getMessage().contains("deadline-job"), e.getMessage());
  }

  @Test
  void a_failed_job_with_no_reason_available_still_produces_a_sane_message() throws Exception {
    fake = new FakeControlPlane();
    fake.setJobPhase("Job", "reasonless-job", "FAILED");
    // No setJobFailureReason call: models a failure the control plane recorded with no reason at
    // all -- no "currentRun" object in the response, not merely an empty string.
    ControlPlaneApi api = new ControlPlaneApi(fake.address());
    RenderedWorkload workload =
        new RenderedWorkload("Job", "reasonless-job", "kind: Job\nname: reasonless-job\n");

    HilmirException e =
        assertThrows(HilmirException.class, () -> WaitPoller.awaitReady(api, workload, out));

    assertTrue(e.getMessage().contains("reasonless-job"), e.getMessage());
    assertTrue(e.getMessage().contains("failed"), e.getMessage());
    assertFalse(e.getMessage().contains("null"), e.getMessage());
  }
}
