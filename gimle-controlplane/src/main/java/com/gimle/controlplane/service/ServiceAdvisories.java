package com.gimle.controlplane.service;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Advisories a Service submission earns without being refused. Both of the conditions checked here
 * describe configurations that are legitimate often enough that rejecting them would break real
 * setups, yet wrong often enough that letting them through in silence costs an operator a
 * connection-time debugging session:
 *
 * <ul>
 *   <li>Two Services in the same tenant fronting the same Deployment. A deliberate shared front
 *       door (a stable name beside a versioned one, a migration in progress) looks exactly like a
 *       copy-paste of the wrong {@code deploymentNames}, and nothing else in the system tells them
 *       apart -- so the overlap is announced, never blocked.
 *   <li>A declared {@code targetPort} no backing instance currently reports. Instance ports arrive
 *       through heartbeats and change over a workload's life, so "nothing reports it right now" is
 *       level-triggered state, not a permanent fact a hard admission error could stand on: a
 *       Service is routinely created before the Deployment behind it exists.
 * </ul>
 */
public final class ServiceAdvisories {

  private ServiceAdvisories() {}

  /**
   * {@code existing} is the Service set as it stands before {@code spec} is stored, so a re-submit
   * of a Service under its own name never reads as overlapping itself.
   */
  public static List<String> forSubmission(
      final ServiceSpec spec, final List<ServiceSpec> existing, final StoreReader store) {
    final List<String> warnings = new ArrayList<>(overlapWarnings(spec, existing));
    unreportedTargetPortWarning(spec, store).ifPresent(warnings::add);
    return List.copyOf(warnings);
  }

  private static List<String> overlapWarnings(
      final ServiceSpec spec, final List<ServiceSpec> existing) {
    final List<String> warnings = new ArrayList<>();
    for (final ServiceSpec other :
        existing.stream()
            .filter(other -> !other.name().equals(spec.name()))
            .filter(other -> other.tenantId().equals(spec.tenantId()))
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList()) {
      final TreeSet<String> shared = new TreeSet<>(other.deploymentNames());
      shared.retainAll(spec.deploymentNames());
      if (!shared.isEmpty()) {
        warnings.add(
            "service "
                + spec.name()
                + " fronts deployment(s) "
                + shared
                + " already fronted by service "
                + other.name()
                + " in the same tenant -- both names route to the same instances");
      }
    }
    return warnings;
  }

  /**
   * Silent when no backing instance reports any port at all: that is the ordinary "Service declared
   * ahead of its workload" case, and warning on it would fire on almost every first submission.
   * Only a backing set that does report ports, none of them the declared one, is worth flagging.
   */
  private static Optional<String> unreportedTargetPortWarning(
      final ServiceSpec spec, final StoreReader store) {
    if (spec.targetPort().isEmpty() || spec.isExternalName()) {
      return Optional.empty();
    }
    final int declared = spec.targetPort().getAsInt();
    final TreeSet<Integer> reported = reportedPorts(spec, store);
    if (reported.isEmpty() || reported.contains(declared)) {
      return Optional.empty();
    }
    return Optional.of(
        "service "
            + spec.name()
            + " declares targetPort "
            + declared
            + ", which no backing instance currently reports (they report "
            + reported
            + ") -- those instances contribute no endpoint until one does");
  }

  private static TreeSet<Integer> reportedPorts(final ServiceSpec spec, final StoreReader store) {
    final TreeSet<Integer> reported = new TreeSet<>();
    for (final String deploymentName : spec.deploymentNames()) {
      for (final InstanceAssignment assignment :
          store.listAssignmentsFor(spec.tenantId(), deploymentName)) {
        store
            .getNodeHeartbeat(assignment.nodeId())
            .map(ObservedHeartbeat::heartbeat)
            .ifPresent(
                heartbeat ->
                    heartbeat.instances().stream()
                        .filter(
                            obs ->
                                obs.deploymentName().equals(assignment.deploymentName())
                                    && obs.instanceIndex() == assignment.instanceIndex()
                                    && obs.tenantId().equals(assignment.tenantId()))
                        .map(InstanceObservation::ports)
                        .forEach(ports -> reported.addAll(ports.values())));
      }
    }
    return reported;
  }
}
