package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Polls a just-applied workload to its own kind-appropriate "ready" predicate for {@code --wait}.
 * Modeled on {@code com.gimle.hilmir.launch.ReadinessPoller}'s own interval/deadline shape, but a
 * new, separate class: that poller is a raw TCP-connect check with no notion of workload lifecycle
 * at all, and is package-private to a different package besides.
 *
 * <p>Deployment/DaemonSet/StatefulSet wait for every instance to report an {@code ACTIVE}
 * observation (an empty instance list is not yet ready, not vacuously ready). Job waits for a
 * terminal {@code phase} and then reports which one it reached: a {@code FAILED} job fails the
 * wait, since a deploy that exits 0 while one of its workloads has terminally failed tells the
 * caller the opposite of what happened. CronJob has no natural "active instance" state at all, so a
 * single successful {@code GET} -- confirming the resource round-trips back from the control plane
 * it was just applied to -- is its whole readiness signal, checked once rather than polled, since
 * nothing about "does this resource still exist" changes on its own between polls the way a
 * Deployment's instance rollout does.
 */
final class WaitPoller {

  /**
   * How long {@code --wait} keeps polling before giving up. Configurable because how long a cluster
   * needs to place and start a workload varies with its size and the workload's own startup cost --
   * five minutes is a default, not a property of the platform.
   */
  public static final String TIMEOUT_PROPERTY = "gimle.hilmir.waitTimeoutMillis";

  private static Duration timeout() {
    return Duration.ofMillis(Long.getLong(TIMEOUT_PROPERTY, Duration.ofMinutes(5).toMillis()));
  }

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private WaitPoller() {}

  static void awaitReady(ControlPlaneApi api, RenderedWorkload workload, PrintStream out) {
    String path = WorkloadKinds.pathPrefix(workload.kind()) + workload.name();
    switch (workload.kind()) {
      case "Deployment", "DaemonSet", "StatefulSet" ->
          pollUntil(api, path, workload, out, WaitPoller::instancesAllActive);
      case "Job" -> awaitJobTerminal(api, path, workload, out);
      case "CronJob" -> awaitExists(api, path, workload, out);
      default ->
          throw new HilmirException("cannot wait on unknown workload kind: " + workload.kind());
    }
  }

  private static void awaitExists(
      ControlPlaneApi api, String path, RenderedWorkload workload, PrintStream out) {
    if (!api.exists(path)) {
      throw new HilmirException(
          "timed out waiting for " + workload.kind() + " " + workload.name() + ": not found");
    }
    out.println(workload.kind() + " " + workload.name() + " ready (exists)");
  }

  private static void pollUntil(
      ControlPlaneApi api,
      String path,
      RenderedWorkload workload,
      PrintStream out,
      Predicate<Map<String, Object>> ready) {
    Duration timeout = timeout();
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (ready.test(api.getObject(path))) {
        out.println(workload.kind() + " " + workload.name() + " ready");
        return;
      }
      if (System.nanoTime() > deadlineNanos) {
        throw new HilmirException(
            "timed out after "
                + timeout
                + " waiting for "
                + workload.kind()
                + " "
                + workload.name()
                + " to become ready");
      }
      sleep();
    }
  }

  /**
   * Ready means the deployment is at its declared size with every replica ACTIVE and nothing left
   * unplaced -- not merely that the instances which happen to exist right now are ACTIVE.
   *
   * <p>Between applying a spec and the reconciler acting on it, the instance list is still the
   * previous one: a scale-up or a version rollout satisfied the old test immediately, so the wait
   * returned before the new replicas existed at all, and every caller that treats it as the
   * completion signal reported success on a deployment that had not started converging.
   */
  private static boolean instancesAllActive(Map<String, Object> status) {
    Object instancesRaw = status.get("instances");
    if (!(instancesRaw instanceof List<?> instances) || instances.isEmpty()) {
      return false;
    }
    if (status.get("unplacedCount") instanceof Number unplaced && unplaced.intValue() > 0) {
      return false;
    }
    if (status.get("spec") instanceof Map<?, ?> spec
        && spec.get("replicas") instanceof Number replicas
        && instances.size() != replicas.intValue()) {
      return false;
    }
    for (Object instanceRaw : instances) {
      Map<String, Object> instance = Json.asObject(instanceRaw);
      Object observation = instance.get("observation");
      if (!(observation instanceof Map<?, ?> observationMap)
          || !"ACTIVE".equals(observationMap.get("lifecycleState"))) {
        return false;
      }
    }
    return true;
  }

  /**
   * A Job's wait ends at either terminal phase, but the two are not the same outcome: reporting a
   * FAILED job as "ready" and exiting 0 hid a terminally failed workload behind a green deploy. The
   * phase is named either way, and a failure fails the wait.
   */
  private static void awaitJobTerminal(
      ControlPlaneApi api, String path, RenderedWorkload workload, PrintStream out) {
    Duration timeout = timeout();
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (true) {
      Object phase = api.getObject(path).get("phase");
      if ("SUCCEEDED".equals(phase)) {
        out.println(workload.kind() + " " + workload.name() + " succeeded");
        return;
      }
      if ("FAILED".equals(phase)) {
        Object reason = api.getObject(path).get("reason");
        throw new HilmirException(
            workload.kind()
                + " "
                + workload.name()
                + " failed"
                + (reason == null ? "" : ": " + reason));
      }
      if (System.nanoTime() > deadlineNanos) {
        throw new HilmirException(
            "timed out after "
                + timeout
                + " waiting for "
                + workload.kind()
                + " "
                + workload.name()
                + " to reach a terminal phase");
      }
      sleep();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while waiting for workload readiness", e);
    }
  }
}
