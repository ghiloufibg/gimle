package com.gimle.hilmir.release;

import com.gimle.core.exception.GimleManifestException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Resolves every {@code ${values.*}} reference in a parsed {@link Bundle} against a merged values
 * map (see {@link ValueOverrides}), and loads each workload's raw manifest text -- from its sibling
 * {@code file:} path, resolved relative to the bundle document's own directory, or straight from
 * its inline {@code manifest:} text -- before substituting and parsing out that workload's own
 * {@code kind}/{@code name}. No template language beyond this single substitution: no conditionals,
 * no loops, no expression evaluation, no nested/indirect references.
 */
final class BundleRenderer {

  private static final Pattern VALUE_REFERENCE = Pattern.compile("\\$\\{values\\.([^}]+)\\}");

  private BundleRenderer() {}

  static RenderedBundle render(Bundle bundle, Map<String, String> values, Path bundleDir) {
    List<RenderedConfigEntry> config = new ArrayList<>();
    for (BundleConfigEntry entry : bundle.config()) {
      String context = "config[" + entry.tenant() + "/" + entry.key() + "].value";
      config.add(
          new RenderedConfigEntry(
              entry.tenant(), entry.key(), substitute(entry.value(), values, context)));
    }
    List<RenderedSecretEntry> secrets = new ArrayList<>();
    for (BundleSecretEntry entry : bundle.secrets()) {
      String context = "secrets[" + entry.tenant() + "/" + entry.key() + "].value";
      secrets.add(
          new RenderedSecretEntry(
              entry.tenant(), entry.key(), substitute(entry.value(), values, context)));
    }
    List<RenderedWorkload> workloads = new ArrayList<>();
    List<BundleWorkload> bundleWorkloads = bundle.workloads();
    for (int i = 0; i < bundleWorkloads.size(); i++) {
      BundleWorkload workload = bundleWorkloads.get(i);
      String rawText = loadRawText(workload, bundleDir);
      String context = "workloads[" + i + "]" + workload.file().map(f -> " (" + f + ")").orElse("");
      String substituted = substitute(rawText, values, context);
      workloads.add(parseKindAndName(substituted, context));
    }
    return new RenderedBundle(
        bundle.name(), bundle.version(), bundle.tenants(), config, secrets, workloads);
  }

  private static String loadRawText(BundleWorkload workload, Path bundleDir) {
    if (workload.inlineManifest().isPresent()) {
      return workload.inlineManifest().get();
    }
    Path file = bundleDir.resolve(workload.file().orElseThrow());
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GimleManifestException(
          "could not read workload manifest " + file + ": " + e.getMessage(), e);
    }
  }

  private static RenderedWorkload parseKindAndName(String yamlText, String context) {
    Object parsed;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      parsed = yaml.load(yamlText);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in " + context, e);
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new GimleManifestException(context + " must contain a YAML mapping at the root");
    }
    Object kind = map.get("kind");
    Object name = map.get("name");
    if (!(kind instanceof String kindString) || kindString.isBlank()) {
      throw new GimleManifestException(context + " has no top-level 'kind' field");
    }
    if (!(name instanceof String nameString) || nameString.isBlank()) {
      throw new GimleManifestException(context + " has no top-level 'name' field");
    }
    return new RenderedWorkload(kindString, nameString, yamlText);
  }

  /**
   * Replaces every {@code ${values.key}} in {@code text} with {@code values.get(key)} -- an
   * unresolved reference (no such key in the merged values map) is a named {@link
   * GimleManifestException}, never a literal {@code ${values.key}} string silently sent downstream.
   */
  static String substitute(String text, Map<String, String> values, String context) {
    Matcher matcher = VALUE_REFERENCE.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = values.get(key);
      if (value == null) {
        throw new GimleManifestException(
            "unresolved value reference '${values."
                + key
                + "}' in "
                + context
                + " -- no default in the bundle's own values: block and no --set/--values override"
                + " supplied");
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
