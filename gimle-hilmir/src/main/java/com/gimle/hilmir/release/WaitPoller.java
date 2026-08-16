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
 * observation (an empty instance list is not yet ready, not vacuously ready). Job waits only for a
 * terminal {@code phase} to be reached, not necessarily {@code SUCCEEDED} -- mirrors {@code kubectl
 * wait}'s own posture of waiting for completion rather than asserting outcome; a caller that cares
 * about success/failure reads the phase back itself afterward. CronJob has no natural "active
 * instance" state at all, so a single successful {@code GET} -- confirming the resource round-trips
 * back from the control plane it was just applied to -- is its whole readiness signal, checked once
 * rather than polled, since nothing about "does this resource still exist" changes on its own
 * between polls the way a Deployment's instance rollout does.
 */
final class WaitPoller {

  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private WaitPoller() {}

  static void awaitReady(ControlPlaneApi api, RenderedWorkload workload, PrintStream out) {
    String path = WorkloadKinds.pathPrefix(workload.kind()) + workload.name();
    switch (workload.kind()) {
      case "Deployment", "DaemonSet", "StatefulSet" ->
          pollUntil(api, path, workload, out, WaitPoller::instancesAllActive);
      case "Job" -> pollUntil(api, path, workload, out, WaitPoller::jobTerminal);
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
    long deadlineNanos = System.nanoTime() + TIMEOUT.toNanos();
    while (true) {
      if (ready.test(api.getObject(path))) {
        out.println(workload.kind() + " " + workload.name() + " ready");
        return;
      }
      if (System.nanoTime() > deadlineNanos) {
        throw new HilmirException(
            "timed out after "
                + TIMEOUT
                + " waiting for "
                + workload.kind()
                + " "
                + workload.name()
                + " to become ready");
      }
      sleep();
    }
  }

  private static boolean instancesAllActive(Map<String, Object> status) {
    Object instancesRaw = status.get("instances");
    if (!(instancesRaw instanceof List<?> instances) || instances.isEmpty()) {
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

  private static boolean jobTerminal(Map<String, Object> status) {
    Object phase = status.get("phase");
    return "SUCCEEDED".equals(phase) || "FAILED".equals(phase);
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
