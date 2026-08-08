package com.gimle.cli;

import java.io.PrintStream;

/**
 * {@code get nodes}, {@code get node-assignments <nodeId>}, {@code cordon <nodeId>}, {@code
 * uncordon <nodeId>}.
 */
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
    OutputFormat.printList(output, client.getList("/nodes"), out);
  }

  public void assignments(String nodeId) {
    OutputFormat.printList(output, client.getList("/nodes/" + nodeId + "/assignments"), out);
  }

  public void cordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/cordon", ""));
    out.println("node/" + nodeId + " cordoned");
  }

  public void uncordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/uncordon", ""));
    out.println("node/" + nodeId + " uncordoned");
  }
}
