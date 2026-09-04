package com.gimle.hugin.model;

import java.util.Locale;

/**
 * One line of the dependency tree: a Service, a deployment it fronts, or an instance of that
 * deployment, flattened to a row with the depth it sits at.
 *
 * <p>Flattened rather than nested because a terminal draws lines, not trees, and a nested model
 * would only be walked back into this shape to render it. {@code state} is a word the screen
 * colours; the model does not decide what a colour means, the same division every other row type
 * here follows.
 */
public record XrayRow(int depth, XrayRow.Kind kind, String label, String detail, String state) {

  /** What a row is, which is also what decides its glyph and how far it is indented. */
  public enum Kind {
    SERVICE,
    DEPLOYMENT,
    INSTANCE,
    /** The heading over deployments no Service fronts -- a finding, not a container. */
    UNFRONTED
  }

  public XrayRow {
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (label == null || detail == null || state == null) {
      throw new IllegalArgumentException("label, detail and state must not be null");
    }
    if (depth < 0) {
      throw new IllegalArgumentException("depth must not be negative");
    }
  }

  /** The text a filter is matched against: everything the row shows. */
  public String searchText() {
    return (label + " " + detail + " " + state).toLowerCase(Locale.ROOT);
  }
}
