package com.gimle.controlplane.service;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Computes a {@link ServiceSpec}'s current live endpoint set from the same assignment/heartbeat/
 * node-registration join {@code ApiServer}'s own {@code GET /endpoints/{name}} route already
 * performs -- shared here so both {@code ServiceReconciler} and {@code ApiServer}'s {@code GET
 * /services/{name}/endpoints} route compute the identical thing off the identical data, rather than
 * each growing its own copy. Unlike {@code /endpoints/{name}}, which lists every assigned instance
 * regardless of health, a Service endpoint set only ever includes an instance currently reporting
 * both alive and ready -- the same "only route to a healthy backend" posture Kubernetes' own
 * EndpointSlice controller has, and {@code DeploymentReconciler#isReady} already established
 * elsewhere in this codebase for a different purpose (gating rolling-update progress).
 *
 * <p>{@code spec.deploymentNames()} names workloads by bare name, not by kind -- a Service can
 * front a Deployment, StatefulSet, or DaemonSet alike (the same kind-agnostic join {@code
 * /endpoints/{name}} and {@code handleAppendInstanceEvent} already perform), so this tries each
 * kind's own assignment collection in turn against every declared name. Checking only Deployment-
 * kind {@code InstanceAssignment} bookkeeping left a Service fronting a DaemonSet or StatefulSet
 * with an empty endpoint set forever, even with every replica genuinely {@code ACTIVE}/ready and
 * reporting a port matching the Service's own {@code targetPort} -- indistinguishable from "no live
 * backing instance yet," a normal transient state this resolver otherwise treats as valid.
 */
public final class ServiceEndpointResolver {

  private ServiceEndpointResolver() {}

  /**
   * One assignment-shaped candidate, kind-erased: a Deployment's {@code InstanceAssignment}, a
   * StatefulSet's {@code StatefulSetAssignment} (both carry a real {@code instanceIndex}), or a
   * DaemonSet's {@code DaemonSetAssignment} (whose own index is always {@code 0}, keyed by node
   * instead -- see that record's own javadoc) all reduce to this same shape for the health/port
   * join below, which never needs to know which kind actually placed a given candidate.
   */
  private record Candidate(
      String deploymentName, int instanceIndex, String nodeId, Optional<String> tenantId) {}

  public static ServiceEndpointResolution resolve(final StoreReader store, final ServiceSpec spec) {
    // An ExternalName Service resolves to exactly its declared external host -- there are no
    // backing instances to join against, and the host is a name the caller's own resolver (or the
    // OS) turns into an address, never one of this cluster's node hosts. With no instance to read
    // a port off, an undeclared targetPort can only mean the port callers already dial.
    if (spec.isExternalName()) {
      final int port = spec.targetPort().orElse(spec.port());
      return new ServiceEndpointResolution(
          List.of(new ServiceEndpoint(spec.externalName().orElseThrow(), port)), List.of());
    }
    final List<ServiceEndpoint> endpoints = new ArrayList<>();
    final List<String> exclusions = new ArrayList<>();
    for (final String deploymentName : spec.deploymentNames()) {
      for (final Candidate candidate : candidatesFor(store, spec.tenantId(), deploymentName)) {
        final Optional<InstanceObservation> observation = readyObservation(store, candidate);
        if (observation.isEmpty()) {
          continue;
        }
        final OptionalInt port = selectPort(spec, observation.get());
        if (port.isEmpty()) {
          exclusions.add(exclusionReason(spec, candidate, observation.get()));
          continue;
        }
        resolveHost(store, candidate.nodeId())
            .ifPresent(
                host ->
                    endpoints.add(
                        new ServiceEndpoint(
                            host, port.getAsInt(), Optional.of(candidate.nodeId()))));
      }
    }
    return new ServiceEndpointResolution(endpoints, exclusions);
  }

  /**
   * Every currently-assigned candidate named {@code deploymentName} under {@code tenantId}, tried
   * across all three placeable kinds a Service may front -- Deployment first (the common case),
   * then StatefulSet, then DaemonSet. A name is unique across kinds within one tenant's own
   * namespace, so at most one of these three ever actually contributes candidates for a given name;
   * trying all three costs nothing when the other two are empty.
   */
  private static List<Candidate> candidatesFor(
      final StoreReader store, final Optional<String> tenantId, final String deploymentName) {
    final List<Candidate> candidates = new ArrayList<>();
    store
        .listAssignmentsFor(tenantId, deploymentName)
        .forEach(
            a ->
                candidates.add(
                    new Candidate(
                        a.deploymentName(), a.instanceIndex(), a.nodeId(), a.tenantId())));
    store
        .listStatefulSetAssignmentsFor(tenantId, deploymentName)
        .forEach(
            (StatefulSetAssignment a) ->
                candidates.add(
                    new Candidate(
                        a.statefulSetName(), a.instanceIndex(), a.nodeId(), a.tenantId())));
    store
        .listDaemonSetAssignmentsFor(tenantId, deploymentName)
        .forEach(
            (DaemonSetAssignment a) ->
                candidates.add(new Candidate(a.daemonSetName(), 0, a.nodeId(), a.tenantId())));
    return candidates;
  }

  private static Optional<InstanceObservation> readyObservation(
      final StoreReader store, final Candidate candidate) {
    return store
        .getNodeHeartbeat(candidate.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(
            heartbeat ->
                heartbeat.instances().stream()
                    .filter(
                        obs ->
                            obs.deploymentName().equals(candidate.deploymentName())
                                && obs.instanceIndex() == candidate.instanceIndex()
                                && obs.tenantId().equals(candidate.tenantId()))
                    .findFirst())
        .filter(InstanceObservation::alive)
        .filter(InstanceObservation::ready);
  }

  /**
   * A declared {@code targetPort} is authoritative: the instance contributes an endpoint on exactly
   * that port, or contributes nothing at all -- never some other port that happens to be the only
   * one it reports, which would silently send traffic somewhere the operator never named.
   *
   * <p>With no {@code targetPort} declared there is nothing to match against: {@link
   * InstanceObservation#ports()} is keyed by the name a port was declared under (e.g. {@code
   * "HTTP_PORT"}) and {@link ServiceSpec} carries no port-name selector -- unlike {@code
   * gimle-gateway}'s own {@code GatewayRoute#portName}, which exists precisely to resolve that same
   * ambiguity for its own routes. An instance reporting exactly one port is then unambiguous; one
   * reporting zero or several contributes no endpoint rather than a guess.
   */
  private static OptionalInt selectPort(
      final ServiceSpec spec, final InstanceObservation observation) {
    final Map<String, Integer> ports = observation.ports();
    if (spec.targetPort().isPresent()) {
      final int declared = spec.targetPort().getAsInt();
      return ports.containsValue(declared) ? OptionalInt.of(declared) : OptionalInt.empty();
    }
    return ports.size() == 1
        ? OptionalInt.of(ports.values().iterator().next())
        : OptionalInt.empty();
  }

  private static String exclusionReason(
      final ServiceSpec spec, final Candidate candidate, final InstanceObservation observation) {
    final String instance =
        candidate.deploymentName()
            + '/'
            + candidate.instanceIndex()
            + " on node "
            + candidate.nodeId();
    final String reported = new TreeSet<>(observation.ports().values()).toString();
    if (spec.targetPort().isPresent()) {
      return "service "
          + spec.name()
          + " declares targetPort "
          + spec.targetPort().getAsInt()
          + ", which instance "
          + instance
          + " does not report; it reports "
          + reported;
    }
    return "service "
        + spec.name()
        + " declares no targetPort, and instance "
        + instance
        + " reports "
        + observation.ports().size()
        + " ports "
        + reported
        + " -- exactly one is needed to pick without guessing";
  }

  private static Optional<String> resolveHost(final StoreReader store, final String nodeId) {
    return store
        .getNodeRegistration(nodeId)
        .flatMap(NodeRegistration::apiAddress)
        .map(ServiceEndpointResolver::hostOnly);
  }

  /** Strips a trailing {@code :port} off a registered {@code host:port} node address. */
  private static String hostOnly(final String hostPort) {
    final int at = hostPort.lastIndexOf(':');
    return at < 0 ? hostPort : hostPort.substring(0, at);
  }
}
