package com.gimle.hugin.model;

/**
 * Which record of "what is going on in this cluster" the activity view is showing. Three genuinely
 * different questions, deliberately kept as one screen: an operator asking any of them is asking
 * the same thing at a different altitude, and each costs a read nobody should pay for while looking
 * at another.
 */
public enum FeedMode {
  /** Authorization decisions: who asked, of what, and whether it was allowed. */
  AUDIT("audit", "/audit"),
  /** Instance lifecycle transitions across every workload, not just one instance's own. */
  LIFECYCLE("lifecycle", "/events"),
  /**
   * Declared alert rules and whether each is currently firing. Costs one request per rule -- the
   * rule list carries no firing state of its own -- which is why this view polls only while open.
   */
  ALERTS("alerts", "/alertrules");

  private final String label;
  private final String route;

  FeedMode(final String label, final String route) {
    this.label = label;
    this.route = route;
  }

  public String label() {
    return label;
  }

  public String route() {
    return route;
  }

  public FeedMode next() {
    FeedMode[] modes = values();
    return modes[(ordinal() + 1) % modes.length];
  }
}
