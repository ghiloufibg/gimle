package com.gimle.holmgang.workload;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.topology.QuotaSpec;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A background write workload that remembers exactly which writes the cluster acknowledged -- the
 * recorded history behind "no acknowledged write is ever lost" assertions: an acknowledged (200)
 * write is a durability promise the store must keep through whatever fault the scenario injects,
 * while an unacknowledged one promises nothing and is deliberately not recorded.
 */
public final class RecordingWorkload implements AutoCloseable {

  private static final QuotaSpec WORKLOAD_QUOTA = QuotaSpec.of(1024L * 1024, 100, 1);

  private final Thread writer;
  private final List<String> acknowledged = new CopyOnWriteArrayList<>();
  private volatile boolean running = true;

  /** Starts upserting uniquely-named tenants through {@code api} every {@code interval}. */
  public static RecordingWorkload tenantWriter(final ClusterApi api, final Duration interval) {
    return new RecordingWorkload(api, interval);
  }

  private RecordingWorkload(final ClusterApi api, final Duration interval) {
    this.writer =
        Thread.ofVirtual()
            .name("holmgang-tenant-writer")
            .start(
                () -> {
                  long sequence = 0;
                  while (running) {
                    final String tenantId = "workload-tenant-" + sequence++;
                    try {
                      if (api.tryPutTenant(tenantId, WORKLOAD_QUOTA) == 200) {
                        acknowledged.add(tenantId);
                      }
                    } catch (final RuntimeException e) {
                      // A transport failure mid-fault is an unacknowledged write: nothing owed.
                    }
                    try {
                      Thread.sleep(interval);
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });
  }

  /** How many writes the cluster has acknowledged so far; grows while the writer runs. */
  public int acknowledgedCount() {
    return acknowledged.size();
  }

  /** Stops the writer and returns every write the cluster acknowledged, oldest first. */
  public List<String> stopAndAcknowledged() {
    running = false;
    writer.interrupt();
    try {
      writer.join(Duration.ofSeconds(10));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted stopping the recording workload", e);
    }
    return List.copyOf(acknowledged);
  }

  @Override
  public void close() {
    running = false;
    writer.interrupt();
  }
}
