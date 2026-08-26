package com.gimle.agent.bifrost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A mutable, in-process {@link ServiceSource} fake: tests mutate it directly (add/remove a service,
 * replace its endpoint set) and then drive {@link BifrostProxy#pollOnce()} to observe how the proxy
 * reacts, with no live control plane involved.
 */
final class InMemoryServiceSource implements ServiceSource {

  private final Map<String, ServiceSummary> summaries = new LinkedHashMap<>();
  private final Map<String, ServiceEndpoints> services = new LinkedHashMap<>();

  /** An untenanted service -- never restricted by any NetworkPolicySpec. */
  synchronized void put(String name, int port, List<ServiceEndpoint> endpoints) {
    put(name, Optional.empty(), Set.of(), port, endpoints);
  }

  synchronized void put(
      String name,
      Optional<String> tenantId,
      Set<String> deploymentNames,
      int port,
      List<ServiceEndpoint> endpoints) {
    put(name, tenantId, deploymentNames, port, false, endpoints);
  }

  synchronized void put(
      String name,
      Optional<String> tenantId,
      Set<String> deploymentNames,
      int port,
      boolean sessionAffinity,
      List<ServiceEndpoint> endpoints) {
    summaries.put(name, new ServiceSummary(name, tenantId, deploymentNames));
    services.put(name, new ServiceEndpoints(name, port, port, sessionAffinity, endpoints));
  }

  synchronized void remove(String name) {
    summaries.remove(name);
    services.remove(name);
  }

  @Override
  public synchronized List<ServiceSummary> listServices() {
    return new ArrayList<>(summaries.values());
  }

  @Override
  public synchronized Optional<ServiceEndpoints> fetchEndpoints(String serviceName) {
    return Optional.ofNullable(services.get(serviceName));
  }
}
