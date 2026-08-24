package com.gimle.cli;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.protocol.Json;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code artifact push <jar>}, {@code artifact list [moduleId]}, {@code artifact get <moduleId>
 * <version> [--to <path>]}, {@code artifact delete <moduleId> <version>} -- the artifact-registry
 * surface, reached through {@code gimle-controlplane}'s {@code /artifacts/*} proxy to Andvari,
 * never Andvari directly (the same routing decision {@link SecretCommand} documents for Fafnir). A
 * distinct top-level verb for the same reason {@code secret} is one: {@code push} has no shape in
 * {@link GimleCli}'s shared three-verb dispatch.
 *
 * <p>{@code push} derives the registry coordinate from the jar's own bundled {@code
 * gimle-module.yaml} rather than taking name/version flags -- the coordinate a jar is stored under
 * and the identity it declares for itself can never drift apart, which is what makes a
 * coordinate-only deployment manifest's {@code module: {name, version}} reference trustworthy. A
 * vessel jar has no {@code gimle-module.yaml} of its own to read a coordinate from, so {@code
 * --vessel --name <moduleId> --version <version>} pushes it under an explicitly given coordinate
 * instead, with no attempt to validate the jar's contents (there is no descriptor to validate
 * against).
 */
public final class ArtifactCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ArtifactCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String verb = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (verb) {
      case "push" -> push(rest);
      case "list" -> list(rest);
      case "get" -> get(rest);
      case "delete" -> delete(rest);
      default -> throw new CliException(usage());
    }
  }

  private void push(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("artifact push requires the path to a module jar");
    }
    Path jar = Path.of(args.get(0));
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of("--vessel"));
    Optional<String> tenantId = Optional.ofNullable(flags.getOrDefault("--tenant", null));

    String moduleId;
    String version;
    if (flags.isSet("--vessel")) {
      moduleId = flags.get("--name");
      version = flags.get("--version");
    } else {
      ModuleArtifact artifact;
      try {
        artifact = ModuleArtifactReader.read(jar);
      } catch (RuntimeException e) {
        throw new CliException("not a pushable module artifact: " + e.getMessage(), e);
      }
      moduleId = artifact.id().name();
      version = artifact.id().version().toString();
    }

    Map<String, String> headers =
        tenantId.map(id -> Map.of("X-Gimle-Artifact-Tenant", id)).orElse(Map.of());
    String response =
        client.expectSuccess(
            client.putFile("/artifacts/" + moduleId + "/" + version, jar, headers));
    Map<String, Object> parsed = Json.asObject(Json.parse(response));
    boolean created = Boolean.TRUE.equals(parsed.get("created"));

    Map<String, Object> body =
        resultBody(created ? "pushed" : "already-present", moduleId, version);
    body.put("sha256", parsed.get("sha256"));
    body.put("sizeBytes", parsed.get("sizeBytes"));
    if (parsed.get("tenantId") != null) {
      body.put("tenantId", parsed.get("tenantId"));
    }
    OutputFormat.printResult(
        output,
        body,
        "artifacts/"
            + moduleId
            + "/"
            + version
            + (created ? " pushed" : " already present (identical bytes)"),
        out);
  }

  private void list(List<String> args) {
    if (args.isEmpty()) {
      String response = client.expectSuccess(client.get("/artifacts"));
      List<Object> moduleIds = Json.asArray(Json.parse(response));
      List<Map<String, Object>> rows =
          moduleIds.stream().map(id -> Map.<String, Object>of("moduleId", id)).toList();
      OutputFormat.printList(output, rows, out);
      return;
    }
    Map<String, Object> response = client.getObject("/artifacts/" + args.get(0));
    OutputFormat.printList(output, Json.asObjectList(response.get("versions")), out);
  }

  private void get(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("artifact get requires <moduleId> <version>");
    }
    String moduleId = args.get(0);
    String version = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of());
    Path target = Path.of(flags.getOrDefault("--to", moduleId + "-" + version + ".jar"));

    Optional<String> sha256 = client.downloadFile("/artifacts/" + moduleId + "/" + version, target);

    Map<String, Object> body = resultBody("downloaded", moduleId, version);
    body.put("file", target.toString());
    sha256.ifPresent(sha -> body.put("sha256", sha));
    OutputFormat.printResult(
        output, body, "artifacts/" + moduleId + "/" + version + " downloaded to " + target, out);
  }

  private void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("artifact delete requires <moduleId> <version>");
    }
    String moduleId = args.get(0);
    String version = args.get(1);
    client.expectSuccess(client.delete("/artifacts/" + moduleId + "/" + version));
    OutputFormat.printResult(
        output,
        resultBody("deleted", moduleId, version),
        "artifacts/" + moduleId + "/" + version + " deleted",
        out);
  }

  private static Map<String, Object> resultBody(String result, String moduleId, String version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "artifact");
    body.put("moduleId", moduleId);
    body.put("version", version);
    return body;
  }

  private static String usage() {
    return """
        usage: gimle artifact <verb> [args]

        verbs:
          push <jar> [--tenant <id>]       (coordinate read from the jar's own gimle-module.yaml)
          push <jar> [--tenant <id>] --vessel --name <moduleId> --version <version>
                                            (vessel jars have no gimle-module.yaml to read)
          list [moduleId]
          get <moduleId> <version> [--to <path>]
          delete <moduleId> <version>
        """;
  }
}
