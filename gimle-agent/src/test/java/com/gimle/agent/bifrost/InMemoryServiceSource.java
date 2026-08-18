package com.gimle.agent.bifrost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A mutable, in-process {@link ServiceSource} fake: tests mutate it directly (add/remove a service,
 * replace its endpoint set) and then drive {@link BifrostProxy#pollOnce()} to observe how the proxy
 * reacts, with no live control plane involved.
 */
final class InMemoryServiceSource implements ServiceSource {

  private final Map<String, ServiceEndpoints> services = new LinkedHashMap<>();

  synchronized void put(String name, int port, List<ServiceEndpoint> endpoints) {
    services.put(name, new ServiceEndpoints(name, port, port, endpoints));
  }

  synchronized void remove(String name) {
    services.remove(name);
  }

  @Override
  public synchronized List<String> listServiceNames() {
    return new ArrayList<>(services.keySet());
  }

  @Override
  public synchronized Optional<ServiceEndpoints> fetchEndpoints(String serviceName) {
    return Optional.ofNullable(services.get(serviceName));
  }
}
