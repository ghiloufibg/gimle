package com.gimle.cli;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Parses an optional trailing {@code --tenant <id>} flag off a by-name resource command's remaining
 * arguments and appends it to the request path as {@code ?tenant=<id>} -- the same query-parameter
 * convention the control plane's own tenant-scoped GET/DELETE routes use. Every resource kind the
 * control plane keys by {@code (tenantId, name)} (Deployment, Job, CronJob, DaemonSet, StatefulSet,
 * Service, NetworkPolicy) needs this: a bare name is no longer enough to address one of a real
 * tenant's resources by itself, since two tenants may share a name. Omitting {@code --tenant}
 * addresses the untenanted namespace, matching the server's own default.
 */
final class TenantQuery {

  private static final String FLAG = "--tenant";

  private TenantQuery() {}

  /**
   * {@code argsAfterName} is whatever remains once the resource name itself has already been
   * consumed by the caller -- this never sees the name, only flags.
   */
  static String appendTo(String path, List<String> argsAfterName) {
    String tenant = valueOf(argsAfterName);
    return tenant == null
        ? path
        : path + "?tenant=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
  }

  /** The bare {@code --tenant} value, or {@code null} if the flag wasn't given. */
  static String valueOf(List<String> args) {
    if (args.isEmpty()) {
      return null;
    }
    Flags flags = Flags.parse(args, Set.of(), "usage: ... [--tenant <id>]");
    return flags.getOrDefault(FLAG, null);
  }
}
