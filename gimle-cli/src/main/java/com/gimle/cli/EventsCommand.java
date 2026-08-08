package com.gimle.cli;

import java.io.PrintStream;

/** {@code events <deploymentName> <instanceIndex>} -- an instance's own lifecycle timeline. */
public final class EventsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public EventsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(String deploymentName, String instanceIndex) {
    String path = "/events?deployment=" + deploymentName + "&instance=" + instanceIndex;
    OutputFormat.printList(output, client.getList(path), out);
  }
}
