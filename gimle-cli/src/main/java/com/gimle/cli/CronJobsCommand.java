package com.gimle.cli;

import com.gimle.core.protocol.Json;
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
 * {@code get cronjobs [name]}, {@code apply -f <file.yaml>}, {@code delete cronjob <name>}, {@code
 * cronjob trigger <name>} -- mirrors {@link JobsCommand} exactly for the CRUD verbs (priority-3
 * design doc §3e), plus the one action {@code trigger} that doesn't fit CRUD: firing a CronJob
 * immediately, bypassing its own schedule (the same operational need {@code kubectl create job
 * --from=cronjob/x} answers). {@code apply} is dispatched here by {@link GimleCli} once it has
 * peeked at the manifest's own {@code kind:} field, the same way {@link JobsCommand#apply} is.
 */
public final class CronJobsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public CronJobsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/cronjobs"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/cronjobs/" + name), out);
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
        client.put("/cronjobs/" + name, new String(manifestBytes, StandardCharsets.UTF_8)));
    OutputFormat.printResult(
        output, resultBody("applied", name), "cronjob/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/cronjobs/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "cronjob/" + name + " deleted", out);
  }

  /**
   * Fires {@code name} immediately, regardless of its own schedule -- {@code
   * CronJobReconciler#triggerNow}'s server-side counterpart. A 409 (concurrencyPolicy: FORBID
   * blocked it) surfaces through {@link ControlPlaneClient#expectSuccess}'s own error path the same
   * way any other rejected write does, not a special case here.
   */
  public void trigger(String name) {
    String responseBody = client.expectSuccess(client.post("/cronjobs/" + name + "/trigger", ""));
    Map<String, Object> body;
    try {
      body = Json.asObject(Json.parse(responseBody));
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new CliException("unexpected response from control plane: " + e.getMessage(), e);
    }
    OutputFormat.printResult(
        output, body, "cronjob/" + name + " triggered -> job/" + body.get("jobName"), out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "cronjob");
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
