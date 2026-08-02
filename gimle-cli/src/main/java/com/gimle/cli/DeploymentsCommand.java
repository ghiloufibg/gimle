package com.gimle.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code get deployments [name]}, {@code apply -f <file.yaml>}, {@code delete deployment <name>}.
 * {@code apply} reads the manifest's own top-level {@code name:} field client-side (via SnakeYAML,
 * the same library the control plane itself uses to parse it) purely to build the URL path -- the
 * original file bytes are PUT verbatim, never re-serialized, so comments/formatting survive.
 */
public final class DeploymentsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public DeploymentsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/deployments"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/deployments/" + name), out);
  }

  public void apply(List<String> args) {
    Path file = requireFileFlag(args);
    byte[] manifestBytes;
    try {
      manifestBytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new CliException("could not read manifest file " + file + ": " + e.getMessage(), e);
    }
    String name = extractName(file, manifestBytes);
    client.expectSuccess(
        client.put("/deployments/" + name, new String(manifestBytes, StandardCharsets.UTF_8)));
    OutputFormat.printResult(
        output, resultBody("applied", name), "deployment/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/deployments/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "deployment/" + name + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "deployment");
    body.put("id", name);
    return body;
  }

  private static Path requireFileFlag(List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (("-f".equals(args.get(i)) || "--file".equals(args.get(i))) && i + 1 < args.size()) {
        return Path.of(args.get(i + 1));
      }
    }
    throw new CliException("apply requires -f <manifest.yaml>");
  }

  private static String extractName(Path file, byte[] manifestBytes) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object parsed;
    try {
      parsed = yaml.load(new ByteArrayInputStream(manifestBytes));
    } catch (RuntimeException e) {
      throw new CliException("malformed manifest " + file + ": " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> map)
        || !(map.get("name") instanceof String name)
        || name.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level 'name' field");
    }
    return name;
  }
}
