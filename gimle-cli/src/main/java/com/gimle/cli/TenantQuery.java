package com.gimle.cli;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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
   * consumed by the caller -- this never sees the name, only flags. {@code usage} is the calling
   * command's own usage line, carried in rather than synthesized here: this class knows the request
   * path but not the verb that built it, so anything it could invent for itself would be the
   * unfilled placeholder a caller used to be shown when its flags failed to parse.
   */
  static String appendTo(String path, List<String> argsAfterName, String usage) {
    return appendTo(path, argsAfterName, usage, Set.of());
  }

  /**
   * {@code alsoRecognized} names the flags the calling verb parses for itself out of this same
   * argument list ({@code rollback}'s own {@code --to-revision}, say). Without it this helper would
   * reject a flag its caller legitimately accepts, since all it knows about on its own is {@code
   * --tenant}.
   */
  static String appendTo(
      String path, List<String> argsAfterName, String usage, Set<String> alsoRecognized) {
    String tenant = valueOf(argsAfterName, usage, alsoRecognized);
    return tenant == null
        ? path
        : path + "?tenant=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
  }

  /** The bare {@code --tenant} value, or {@code null} if the flag wasn't given. */
  static String valueOf(List<String> args, String usage) {
    return valueOf(args, usage, Set.of());
  }

  static String valueOf(List<String> args, String usage, Set<String> alsoRecognized) {
    if (args.isEmpty()) {
      return null;
    }
    // parseKnown, not parse: --tenant is the only flag any of these paths accepts, and an
    // unrecognized one used to be parsed into a map nothing reads -- accepted in silence,
    // leaving a caller believing it had scoped a request it had not.
    Set<String> recognized = new LinkedHashSet<>(alsoRecognized);
    recognized.add(FLAG);
    Flags flags = Flags.parseKnown(args, recognized, usage);
    return flags.getOrDefault(FLAG, null);
  }
}
