package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get rolebindings [id]}, {@code set rolebinding <id> --subject user:<name>|group:<name>
 * --role <name>}, {@code delete rolebinding <id>}.
 */
public final class RoleBindingsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public RoleBindingsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    String id = GimleCli.requireAtMostOne(args, "rolebinding");
    if (id == null) {
      OutputFormat.printList(output, client.getList("/rolebindings"), out);
      return;
    }
    OutputFormat.printObject(output, client.getObject("/rolebindings/" + id), out);
  }

  public void set(List<String> args) {
    String usage = "set rolebinding requires <id> --subject user:<name>|group:<name> --role <name>";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String id = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage);
    String subject = flags.get("--subject");
    String roleName = flags.get("--role");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("subject", subject);
    body.put("roleName", roleName);

    client.expectSuccess(client.put("/rolebindings/" + id, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", id), "rolebinding/" + id + " configured", out);
    RbacWarnings.warnIfPlaintext(out);
  }

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: RoleBinding}. Uses {@code name:} for the
   * identifier, matching every other manifest kind's own top-level field, even though {@code
   * set}/{@code get}/{@code delete} above call it {@code id}.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String id = requireString(root, "name", file);
    String subject = requireString(root, "subject", file);
    String roleName = requireString(root, "roleName", file);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("subject", subject);
    body.put("roleName", roleName);

    ApiResponse response = client.put("/rolebindings/" + id, Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", id), "rolebinding/" + id + " applied", out);
    RbacWarnings.warnIfPlaintext(out);
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
  }

  public void delete(String id) {
    client.expectSuccess(client.delete("/rolebindings/" + id));
    OutputFormat.printResult(
        output, resultBody("deleted", id), "rolebinding/" + id + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String id) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "rolebinding");
    body.put("id", id);
    return body;
  }
}
