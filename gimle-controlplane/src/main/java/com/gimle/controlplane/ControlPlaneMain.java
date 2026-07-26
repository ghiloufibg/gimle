package com.gimle.controlplane;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.StateStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's entry point (design §9): wires the state store, scheduler, the three
 * reconcilers, and the API server together. The three reconcilers are independent in what they each
 * compute (design §7), but share one ticker thread here rather than three separate timers -- the
 * same "one shared ticker, independent per-check logic" shape {@code gimle-worker}'s {@code
 * ProbeLoop} already established; fixed-interval ticking is what the level-triggered design needs,
 * not literal thread independence. Tick order matters for same-tick convergence, not for
 * correctness across ticks: {@link ReplicaCountReconciler} and {@link HealthReconciler} release
 * assignments that are missing or unhealthy, and {@link DeploymentReconciler} -- run last -- fills
 * every gap that exists by the time it runs, whether that gap is from a prior tick or this one.
 */
public final class ControlPlaneMain {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneMain.class);

  // See design §11.3: heartbeats every 5s, a node considered dark after 3 missed ones.
  private static final Duration NODE_DARK_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(2);

  private ControlPlaneMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: ControlPlaneMain <port> <stateDir>");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path stateDir = Path.of(args[1]);

    StateStore store = new StateStore(stateDir);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler = new DeploymentReconciler(store, scheduler);
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT);
    HealthReconciler healthReconciler = new HealthReconciler(store);

    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-reconcile-tick").unstarted(r));
    ticker.scheduleAtFixedRate(
        () -> reconcileTick(replicaCountReconciler, healthReconciler, deploymentReconciler),
        0,
        RECONCILE_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);

    ApiServer apiServer = new ApiServer(store, port);
    apiServer.start();
    log.info("control plane listening on port {} (state: {})", apiServer.port(), stateDir);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      apiServer.close();
                      ticker.shutdownNow();
                    }));
  }

  private static void reconcileTick(
      ReplicaCountReconciler replicaCountReconciler,
      HealthReconciler healthReconciler,
      DeploymentReconciler deploymentReconciler) {
    try {
      replicaCountReconciler.reconcileOnce();
      healthReconciler.reconcileOnce();
      deploymentReconciler.reconcileOnce();
    } catch (RuntimeException e) {
      log.error("reconcile tick failed: {}", e.getMessage(), e);
    }
  }
}
