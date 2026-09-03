package com.gimle.hugin.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One browsable kind: what an operator types after {@code :}, the route that lists it, and the
 * columns its table shows.
 *
 * <p>A record rather than an enum because the set is not fixed at compile time -- a cluster's own
 * registered custom kinds are discovered from {@code /kinddefinitions} at runtime and browse
 * through exactly the same path as the built-in ones, printing the columns their own definition
 * declares.
 *
 * <p>The built-in list below is every collection route the control plane actually serves. Two
 * absences are deliberate rather than oversights, and both are properties of the API rather than
 * choices made here: ConfigMaps and secrets are addressable only one name at a time ({@code
 * /configmaps/{name}}, {@code /secrets/{tenant}/{key}}) with no collection route to list, and the
 * artifact catalog answers with bare module-id strings rather than objects, so it has no columns to
 * draw and its versions cost a request per module.
 */
public record ResourceKind(
    String key,
    String label,
    String route,
    Optional<String> envelope,
    String namePath,
    Optional<String> tenantPath,
    List<ResourceColumn> columns,
    boolean custom) {

  public ResourceKind {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (columns == null || columns.isEmpty()) {
      throw new IllegalArgumentException("a kind must declare at least one column");
    }
    columns = List.copyOf(columns);
  }

  private static ResourceKind builtIn(
      final String key,
      final String label,
      final String route,
      final String namePath,
      final String tenantPath,
      final ResourceColumn... columns) {
    return new ResourceKind(
        key,
        label,
        route,
        Optional.empty(),
        namePath,
        Optional.ofNullable(tenantPath),
        List.of(columns),
        false);
  }

  /**
   * Every kind the control plane lists as objects. Each column path below is read off the response
   * the route actually serves, not off a manifest's own field names -- the two differ (a tenant's
   * manifest declares a quota; the route reports that quota alongside live usage).
   */
  public static List<ResourceKind> builtIns() {
    return List.of(
        builtIn(
            "tenants",
            "tenants",
            "/tenants",
            "id",
            "id",
            ResourceColumn.of("NAME", "id"),
            ResourceColumn.of("POSTURE", "isolationPosture"),
            ResourceColumn.of("INSTANCES", "usage.instances"),
            ResourceColumn.of("MAX", "quota.maxInstances"),
            ResourceColumn.of("OVER QUOTA", "quotaViolating")),
        builtIn(
            "cronjobs",
            "cron jobs",
            "/cronjobs",
            "spec.name",
            "spec.tenantId",
            ResourceColumn.of("NAME", "spec.name"),
            ResourceColumn.of("TENANT", "spec.tenantId"),
            ResourceColumn.wide("SCHEDULE", "spec.schedule"),
            ResourceColumn.of("SUSPENDED", "spec.suspend"),
            ResourceColumn.of("CONCURRENCY", "spec.concurrencyPolicy")),
        builtIn(
            "limitranges",
            "limit ranges",
            "/limitranges",
            "tenantId",
            "tenantId",
            ResourceColumn.of("TENANT", "tenantId"),
            ResourceColumn.of("MIN REQ CPU", "minRequest.cpu"),
            ResourceColumn.of("MAX REQ CPU", "maxRequest.cpu"),
            ResourceColumn.of("MIN LIM MEM", "minLimit.memory"),
            ResourceColumn.of("MAX LIM MEM", "maxLimit.memory")),
        builtIn(
            "networkpolicies",
            "network policies",
            "/networkpolicies",
            "name",
            "tenantId",
            ResourceColumn.of("NAME", "name"),
            ResourceColumn.of("TENANT", "tenantId"),
            ResourceColumn.wide("ALLOWED CALLERS", "allowedCallerTenantIds"),
            ResourceColumn.wide("DEPLOYMENTS", "deploymentNames")),
        builtIn(
            "ingresses",
            "ingresses",
            "/ingresses",
            "name",
            "tenantId",
            ResourceColumn.of("NAME", "name"),
            ResourceColumn.of("TENANT", "tenantId"),
            ResourceColumn.wide("HOST", "host"),
            ResourceColumn.of("ROUTES", "routes")),
        builtIn(
            "roles",
            "roles",
            "/roles",
            "name",
            null,
            ResourceColumn.wide("NAME", "name"),
            ResourceColumn.of("PERMISSIONS", "permissions")),
        builtIn(
            "rolebindings",
            "role bindings",
            "/rolebindings",
            "id",
            null,
            ResourceColumn.of("ID", "id"),
            ResourceColumn.wide("SUBJECT", "subject"),
            ResourceColumn.of("ROLE", "roleName")),
        builtIn(
            "accounts",
            "accounts",
            "/accounts",
            "username",
            null,
            ResourceColumn.of("USERNAME", "username"),
            ResourceColumn.wide("GROUPS", "groups")),
        builtIn(
            "kinddefinitions",
            "kind definitions",
            "/kinddefinitions",
            "kindName",
            null,
            ResourceColumn.of("KIND", "kindName"),
            ResourceColumn.of("SCOPE", "scope"),
            ResourceColumn.of("PLURAL", "names.plural"),
            ResourceColumn.wide("DESCRIPTION", "description")),
        new ResourceKind(
            "volumes",
            "volumes",
            "/volumes",
            Optional.of("volumes"),
            "volumeName",
            Optional.of("tenantId"),
            List.of(
                ResourceColumn.of("STATEFULSET", "statefulSet"),
                ResourceColumn.of("IDX", "instanceIndex"),
                ResourceColumn.of("TENANT", "tenantId"),
                ResourceColumn.of("NODE", "nodeId"),
                ResourceColumn.of("IN USE", "inUse")),
            false));
  }

  /**
   * A kind the cluster itself registered, browsed the same way a built-in one is. Its columns are
   * whatever its own definition declares as print columns, after the name and tenant every custom
   * resource carries -- so what an operator sees is what whoever registered the kind chose to
   * surface, not a guess made here about which of its spec fields matter.
   */
  public static ResourceKind fromDefinition(
      final String kindName,
      final String key,
      final Optional<String> description,
      final List<ResourceColumn> printColumns) {
    List<ResourceColumn> columns = new ArrayList<>();
    columns.add(ResourceColumn.wide("NAME", "name"));
    columns.add(ResourceColumn.of("TENANT", "tenantId"));
    columns.addAll(printColumns);
    return new ResourceKind(
        key,
        description.filter(text -> !text.isBlank()).orElse(kindName),
        "/resources/" + kindName,
        Optional.empty(),
        "name",
        Optional.of("tenantId"),
        columns,
        true);
  }
}
