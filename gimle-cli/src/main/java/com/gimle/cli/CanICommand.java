package com.gimle.cli;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code can-i <verb> <resource> [--tenant <id>] [--target <id>]} -- the {@code kubectl auth can-i}
 * analogue: asks the control plane's self-subject access review whether the calling identity would
 * be authorized for an action, without performing it. The answer is computed server-side by the
 * identical authorization walk every real request goes through, so it can never drift from what
 * enforcement would actually decide. Verb and resource are matched case-insensitively, with {@code
 * -} accepted for {@code _} ({@code network-policy} and {@code NETWORK_POLICY} both work) and a
 * plural {@code s} tolerated ({@code deployments} works), so the nouns this CLI's other verbs
 * already use spell valid questions here too.
 */
public final class CanICommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public CanICommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    String usage = "usage: gimle can-i <verb> <resource> [--tenant <id>] [--target <id>]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    Verb verb = parseVerb(args.get(0));
    ResourceKind resource = parseResource(args.get(1));
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of(), usage);
    String tenant = flags.getOrDefault("--tenant", null);
    String target = flags.getOrDefault("--target", null);

    StringBuilder path =
        new StringBuilder("/authz/can-i?resource=")
            .append(resource.name())
            .append("&verb=")
            .append(verb.name());
    if (tenant != null && !tenant.isBlank()) {
      path.append("&tenant=").append(URLEncoder.encode(tenant, StandardCharsets.UTF_8));
    }
    if (target != null && !target.isBlank()) {
      path.append("&target=").append(URLEncoder.encode(target, StandardCharsets.UTF_8));
    }

    Map<String, Object> body = client.getObject(path.toString());
    boolean allowed = Boolean.TRUE.equals(body.get("allowed"));
    OutputFormat.printResult(output, body, allowed ? "yes" : "no", out);
  }

  private static Verb parseVerb(String raw) {
    try {
      return Verb.valueOf(normalize(raw));
    } catch (IllegalArgumentException e) {
      throw new CliException(
          "unknown verb: " + raw + " (expected one of " + names(Verb.values()) + ")");
    }
  }

  private static ResourceKind parseResource(String raw) {
    String normalized = normalize(raw);
    try {
      return ResourceKind.valueOf(normalized);
    } catch (IllegalArgumentException first) {
      // Tolerate the plural nouns the CLI's own get/set/delete verbs use (deployments, roles...).
      if (normalized.endsWith("S")) {
        try {
          return ResourceKind.valueOf(normalized.substring(0, normalized.length() - 1));
        } catch (IllegalArgumentException ignored) {
          // fall through to the error below, naming the original input
        }
      }
      throw new CliException(
          "unknown resource: " + raw + " (expected one of " + names(ResourceKind.values()) + ")");
    }
  }

  private static String normalize(String raw) {
    return raw.toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static String names(Enum<?>[] values) {
    return String.join(", ", Arrays.stream(values).map(Enum::name).toList());
  }
}
