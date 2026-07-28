package com.gimle.cli;

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
    OutputFormat.printList(output, client.getList("/nodes"), out);
  }

  public void assignments(String nodeId) {
    OutputFormat.printList(output, client.getList("/nodes/" + nodeId + "/assignments"), out);
  }
}
