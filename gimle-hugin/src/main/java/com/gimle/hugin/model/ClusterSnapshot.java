package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One immutable read of the whole cluster. The poller publishes these; the render loop reads
 * whichever is current and never blocks on I/O of its own -- the same read-a-snapshot,
 * return-a-result discipline the platform's reconcilers follow, and what lets every renderer be
 * tested as a pure function.
 *
 * <p>{@code fetchedAt} empty means no poll has ever succeeded. {@code staleReason} present means
 * the most recent poll failed and these rows are the last good ones, from {@code fetchedAt}: a
 * failed poll never clears the screen, it ages the data and says why.
 */
public record ClusterSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    List<NodeRow> nodes,
    List<InstanceRow> instances,
    List<WorkloadRow> workloads,
    Optional<String> staleReason)
    implements Staleable<ClusterSnapshot> {

  public ClusterSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    nodes = List.copyOf(nodes);
    instances = List.copyOf(instances);
    workloads = List.copyOf(workloads);
  }

  /** The starting state: connected to nothing yet, showing nothing. */
  public static ClusterSnapshot connecting(final String serverAddress) {
    return new ClusterSnapshot(
        serverAddress,
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        Optional.of("connecting"));
  }

  /** This snapshot's rows, re-labelled as the last good data behind a now-failing poll. */
  @Override
  public ClusterSnapshot stale(final String reason) {
    return new ClusterSnapshot(
        serverAddress, fetchedAt, nodes, instances, workloads, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /**
   * The rows to draw, in the order to draw them. Filtering and ordering live together in one method
   * because two callers need the same answer -- the screen that renders the rows and the key
   * handler that moves the cursor through them -- and a cursor stepping through a differently
   * ordered list than the one on screen would land on a row the operator did not select.
   */
  public List<InstanceRow> instancesMatching(final String filter, final SortKey sort) {
    List<InstanceRow> matching =
        filter == null || filter.isBlank()
            ? instances
            : instances.stream()
                .filter(row -> row.searchText().contains(filter.toLowerCase(Locale.ROOT)))
                .toList();
    return matching.stream().sorted(sort.comparator()).toList();
  }

  /**
   * The node rows to draw, in the order to draw them -- filtered and ordered in one place for the
   * same reason the instance rows are: the screen and the key handler must step through the same
   * list or the cursor lands on a row the operator did not pick.
   */
  public List<NodeRow> nodesMatching(
      final String filter, final NodeSortKey sort, final Instant now) {
    List<NodeRow> matching =
        filter == null || filter.isBlank()
            ? nodes
            : nodes.stream()
                .filter(
                    node ->
                        node.nodeId()
                            .toLowerCase(Locale.ROOT)
                            .contains(filter.toLowerCase(Locale.ROOT)))
                .toList();
    return matching.stream().sorted(sort.comparator(now)).toList();
  }

  /** Only the workloads not running what they were asked to run -- the rest need no line. */
  public List<WorkloadRow> unsettledWorkloads() {
    return workloads.stream().filter(workload -> !workload.settled()).toList();
  }

  /** Replicas the scheduler has not placed anywhere, across every workload. */
  public int unplacedCount() {
    return workloads.stream().mapToInt(WorkloadRow::unplacedCount).sum();
  }

  public Optional<InstanceRow> find(final InstanceKey key) {
    return instances.stream().filter(row -> row.key().equals(key)).findFirst();
  }
}
