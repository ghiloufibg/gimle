package com.gimle.hilmir.extension;

import com.gimle.core.module.Version;

/**
 * Builds the {@code kind: Bundle} YAML text {@code hilmir enable gateway} deploys/upgrades --
 * reproducing {@code gimle-gateway}'s own real manifest shape ({@code DaemonSet}, {@code tenantId:
 * gimle-system}, {@code placement.requiredLabels: [edge]}, no {@code artifactPath} so admission
 * resolves the module through the registry coordinate it was just pushed to) plus the two {@code
 * gimle-system/gateway.port}/{@code gateway.routes} config entries {@code GatewayHooks} reads at
 * startup, with sensible defaults.
 *
 * <p>Text, not a directly-constructed {@code Bundle} record: {@code
 * com.gimle.hilmir.release.BundleRenderer}'s {@code ${values.*}} substitution logic is
 * package-private to {@code com.gimle.hilmir.release}, so feeding generated YAML back through that
 * package's own unmodified {@code BundleParser}/{@code BundleRenderer} pipeline (reached here only
 * indirectly, via {@code DeployCommand.run}/{@code UpgradeCommand.run}'s own {@code -f} flag) is
 * the only way to reuse that substitution mechanism unmodified from outside its package -- rather
 * than forking it or hand-rolling a second one.
 *
 * <p>Built by plain concatenation (a {@link StringBuilder}, one YAML line per {@code append})
 * rather than a text block fed through {@code String#formatted} -- a multi-line format string
 * containing its own literal newlines trips SpotBugs's {@code VA_FORMAT_STRING_USES_NEWLINE} check,
 * and this YAML genuinely needs literal {@code \n}, not the platform-dependent {@code %n} that
 * check wants instead.
 */
final class GatewayBundleTemplate {

  static final String RELEASE_NAME = "gimle-gateway";
  static final String WORKLOAD_NAME = "gimle-gateway";
  static final String TENANT_ID = "gimle-system";

  private static final String DEFAULT_PORT = "8090";
  private static final String DEFAULT_ROUTES = "";

  private GatewayBundleTemplate() {}

  static String render(String moduleName, Version moduleVersion) {
    StringBuilder yaml = new StringBuilder();
    yaml.append("kind: Bundle\n");
    yaml.append("name: ").append(RELEASE_NAME).append('\n');
    yaml.append("version: ").append(moduleVersion).append('\n');
    yaml.append("values:\n");
    yaml.append("  port: \"").append(DEFAULT_PORT).append("\"\n");
    yaml.append("  routes: \"").append(DEFAULT_ROUTES).append("\"\n");
    yaml.append("config:\n");
    appendConfigEntry(yaml, "gateway.port", "${values.port}");
    appendConfigEntry(yaml, "gateway.routes", "${values.routes}");
    yaml.append("workloads:\n");
    yaml.append("  - manifest: |\n");
    appendIndented(yaml, workloadManifestYaml(moduleName, moduleVersion));
    return yaml.toString();
  }

  private static void appendConfigEntry(StringBuilder yaml, String key, String value) {
    yaml.append("  - tenant: ").append(TENANT_ID).append('\n');
    yaml.append("    key: ").append(key).append('\n');
    yaml.append("    value: \"").append(value).append("\"\n");
  }

  private static String workloadManifestYaml(String moduleName, Version moduleVersion) {
    StringBuilder yaml = new StringBuilder();
    yaml.append("kind: DaemonSet\n");
    yaml.append("name: ").append(WORKLOAD_NAME).append('\n');
    yaml.append("module:\n");
    yaml.append("  name: ").append(moduleName).append('\n');
    yaml.append("  version: ").append(moduleVersion).append('\n');
    yaml.append("placement:\n");
    yaml.append("  requiredLabels:\n");
    yaml.append("    - edge\n");
    yaml.append("tenantId: ").append(TENANT_ID);
    return yaml.toString();
  }

  /**
   * Indents every line of {@code text} six spaces -- a YAML block-scalar body under {@code -
   * manifest: |}.
   */
  private static void appendIndented(StringBuilder yaml, String text) {
    for (String line : text.split("\n")) {
      yaml.append("      ").append(line).append('\n');
    }
  }
}
