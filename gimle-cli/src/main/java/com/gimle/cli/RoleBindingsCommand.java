package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
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
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/rolebindings"), out);
      return;
    }
    String id = args.get(0);
    OutputFormat.printObject(output, client.getObject("/rolebindings/" + id), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set rolebinding requires <id>");
    }
    String id = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
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
