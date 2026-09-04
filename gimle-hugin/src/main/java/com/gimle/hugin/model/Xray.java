package com.gimle.hugin.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Joins two readings the view already has -- the Services and the running instances -- into the
 * chain a call actually travels: Service, the deployments it fronts, the instances of each.
 *
 * <p>The join is the whole point. Both halves are already on screen elsewhere and neither answers
 * the question this does, because the two findings that matter live in the gap between them: a
 * Service naming a deployment that is not running, and a deployment nothing can reach because no
 * Service fronts it. Each looks healthy in its own table.
 *
 * <p>A pure function of the two snapshots -- no read of its own, so opening it costs exactly the
 * Services poll the services screen already costs and nothing more.
 */
public final class Xray {

  private Xray() {}

  public static List<XrayRow> rows(
      final ServiceSnapshot services, final ClusterSnapshot cluster, final String filter) {
    List<XrayRow> rows = new ArrayList<>();
    Set<String> fronted = new LinkedHashSet<>();

    for (ServiceRow service : services.services()) {
      rows.add(serviceRow(service));
      for (String deployment : service.deploymentNames()) {
        fronted.add(key(service.tenantId(), deployment));
        rows.add(deploymentRow(deployment, service.tenantId(), cluster));
        rows.addAll(instanceRows(deployment, service.tenantId(), cluster, 2));
      }
    }

    List<WorkloadRow> unfronted =
        cluster.workloads().stream()
            .filter(workload -> !fronted.contains(key(workload.tenantId(), workload.name())))
            .sorted(
                Comparator.comparing((WorkloadRow row) -> row.tenantId().orElse(""))
                    .thenComparing(WorkloadRow::name))
            .toList();
    if (!unfronted.isEmpty()) {
      // Said as a group with its own heading rather than left off: a workload nothing fronts is
      // reachable only by whatever already knows its instances, which is a finding about the
      // cluster's wiring and not an absence of information.
      rows.add(
          new XrayRow(
              0,
              XrayRow.Kind.UNFRONTED,
              "fronted by no Service",
              unfronted.size() + (unfronted.size() == 1 ? " workload" : " workloads"),
              ""));
      for (WorkloadRow workload : unfronted) {
        rows.add(deploymentRow(workload.name(), workload.tenantId(), cluster));
        rows.addAll(instanceRows(workload.name(), workload.tenantId(), cluster, 2));
      }
    }

    return filter == null || filter.isBlank() ? rows : narrow(rows, filter);
  }

  /**
   * Keeps the rows that match and every ancestor of one, so a matched instance is still shown under
   * the Service and deployment it belongs to. A tree filtered to bare matches loses the only thing
   * it was drawn for.
   */
  private static List<XrayRow> narrow(final List<XrayRow> rows, final String filter) {
    String needle = filter.toLowerCase(Locale.ROOT);
    boolean[] keep = new boolean[rows.size()];
    for (int index = 0; index < rows.size(); index++) {
      if (!rows.get(index).searchText().contains(needle)) {
        continue;
      }
      keep[index] = true;
      int depth = rows.get(index).depth();
      for (int above = index - 1; above >= 0 && depth > 0; above--) {
        if (rows.get(above).depth() < depth) {
          keep[above] = true;
          depth = rows.get(above).depth();
        }
      }
    }
    List<XrayRow> kept = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
      if (keep[index]) {
        kept.add(rows.get(index));
      }
    }
    return kept;
  }

  private static XrayRow serviceRow(final ServiceRow service) {
    StringBuilder detail = new StringBuilder();
    service.tenantId().ifPresent(tenant -> detail.append(tenant).append("  "));
    if (service.port() > 0) {
      detail.append(':').append(service.port()).append("  ");
    }
    detail.append(
        service.endpointCount().isPresent()
            ? service.endpointCount().getAsInt() + " endpoints"
            : "endpoints unreadable");
    return new XrayRow(
        0, XrayRow.Kind.SERVICE, service.name(), detail.toString().trim(), service.state());
  }

  /**
   * A deployment a Service names but that is running nothing reads as {@code NOT RUNNING}, and one
   * the cluster has never heard of as {@code NOT FOUND}. The two are different mistakes -- a scaled
   * -to-zero workload against a Service pointed at a name that does not exist -- and telling them
   * apart is most of the value of looking here at all.
   */
  private static XrayRow deploymentRow(
      final String name, final Optional<String> tenantId, final ClusterSnapshot cluster) {
    Optional<WorkloadRow> workload =
        cluster.workloads().stream()
            .filter(row -> row.name().equals(name) && sameTenant(row.tenantId(), tenantId))
            .findFirst();
    long running =
        cluster.instances().stream()
            .filter(row -> row.deploymentName().equals(name))
            .filter(row -> sameTenant(row.tenantId(), tenantId))
            .count();
    if (workload.isEmpty()) {
      return new XrayRow(1, XrayRow.Kind.DEPLOYMENT, name, "no such workload", "NOT FOUND");
    }
    String detail = running + " of " + workload.get().desiredReplicas() + " running";
    return new XrayRow(1, XrayRow.Kind.DEPLOYMENT, name, detail, running == 0 ? "NOT RUNNING" : "");
  }

  private static List<XrayRow> instanceRows(
      final String deployment,
      final Optional<String> tenantId,
      final ClusterSnapshot cluster,
      final int depth) {
    return cluster.instances().stream()
        .filter(row -> row.deploymentName().equals(deployment))
        .filter(row -> sameTenant(row.tenantId(), tenantId))
        .sorted(Comparator.comparingInt(InstanceRow::instanceIndex))
        .map(
            row ->
                new XrayRow(
                    depth,
                    XrayRow.Kind.INSTANCE,
                    deployment + "/" + row.instanceIndex(),
                    row.nodeId() + (row.ready() ? "  ready" : "  not ready"),
                    row.lifecycleState()))
        .toList();
  }

  /**
   * A Service declares no tenant when it fronts the untenanted namespace, and so does a workload
   * there -- so absent matches absent, and a Service that does declare one only ever reaches that
   * tenant's own workloads.
   */
  private static boolean sameTenant(final Optional<String> left, final Optional<String> right) {
    return left.orElse("").equals(right.orElse(""));
  }

  private static String key(final Optional<String> tenantId, final String name) {
    return tenantId.orElse("") + "/" + name;
  }
}
