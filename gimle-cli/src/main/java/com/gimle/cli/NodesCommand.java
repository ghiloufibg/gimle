package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;

/** {@code get nodes}, {@code get node-assignments <nodeId>}. */
public final class NodesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public NodesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void list() {
    String body = client.expectSuccess(client.get("/nodes"));
    OutputFormat.printList(output, Json.asObjectList(Json.parse(body)), out);
  }

  public void assignments(String nodeId) {
    String body = client.expectSuccess(client.get("/nodes/" + nodeId + "/assignments"));
    OutputFormat.printList(output, Json.asObjectList(Json.parse(body)), out);
  }
}
