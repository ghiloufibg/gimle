package com.gimle.controlplane.service;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 */
public final class ServiceEndpointResolver {

  private ServiceEndpointResolver() {}

  public static List<ServiceEndpoint> resolve(StoreReader store, ServiceSpec spec) {
    // An ExternalName Service resolves to exactly its declared external host at targetPort --
    // there are no backing instances to join against, and the host is a name the caller's own
    // resolver (or the OS) turns into an address, never one of this cluster's node hosts.
    if (spec.isExternalName()) {
      return List.of(new ServiceEndpoint(spec.externalName().orElseThrow(), spec.targetPort()));
    }
    List<ServiceEndpoint> endpoints = new ArrayList<>();
    for (String deploymentName : spec.deploymentNames()) {
      for (InstanceAssignment assignment :
          store.listAssignmentsFor(spec.tenantId(), deploymentName)) {
        readyObservation(store, assignment)
            .flatMap(ServiceEndpointResolver::solePort)
            .ifPresent(
                port ->
                    resolveHost(store, assignment.nodeId())
                        .ifPresent(
                            host ->
                                endpoints.add(
                                    new ServiceEndpoint(
                                        host, port, Optional.of(assignment.nodeId())))));
      }
    }
    return endpoints;
  }

  private static Optional<InstanceObservation> readyObservation(
      StoreReader store, InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(
            heartbeat ->
                heartbeat.instances().stream()
                    .filter(
                        obs ->
                            obs.deploymentName().equals(assignment.deploymentName())
                                && obs.instanceIndex() == assignment.instanceIndex())
                    .findFirst())
        .filter(InstanceObservation::alive)
        .filter(InstanceObservation::ready);
  }

  /**
   * {@link InstanceObservation#ports()} is keyed by the vessel env-var name a port was declared
   * under (e.g. {@code "HTTP_PORT"}), and {@link ServiceSpec} carries no selector naming which
   * declared port is "the" service port -- unlike {@code gimle-gateway}'s own {@code
   * GatewayRoute#portName}, which exists precisely to resolve that same ambiguity for its own
   * routes. An instance declaring exactly one port is unambiguous; one declaring zero or several
   * contributes no endpoint this tick rather than guessing which one a Service means.
   */
  private static Optional<Integer> solePort(InstanceObservation observation) {
    Map<String, Integer> ports = observation.ports();
    return ports.size() == 1 ? Optional.of(ports.values().iterator().next()) : Optional.empty();
  }

  private static Optional<String> resolveHost(StoreReader store, String nodeId) {
    return store
        .getNodeRegistration(nodeId)
        .flatMap(NodeRegistration::apiAddress)
        .map(ServiceEndpointResolver::hostOnly);
  }

  /** Strips a trailing {@code :port} off a registered {@code host:port} node address. */
  private static String hostOnly(String hostPort) {
    int at = hostPort.lastIndexOf(':');
    return at < 0 ? hostPort : hostPort.substring(0, at);
  }
}
