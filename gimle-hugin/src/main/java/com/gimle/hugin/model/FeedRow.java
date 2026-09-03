package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Locale;

/**
 * One line of the activity view, whichever feed it came from.
 *
 * <p>The three feeds answer different questions but read the same shape on screen -- when, who or
 * what, the action, the verdict, and the subject -- so they share one row rather than one table
 * each. Each reader maps its own response into this; the verdict's colour is the screen's business,
 * not the model's, which is why no style travels here.
 */
public record FeedRow(Instant at, String actor, String action, String verdict, String subject) {

  public FeedRow {
    if (at == null) {
      throw new IllegalArgumentException("at must not be null");
    }
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("actor must not be blank");
    }
    if (action == null || verdict == null || subject == null) {
      throw new IllegalArgumentException("action, verdict and subject must not be null");
    }
  }

  /** The text a filter is matched against: everything an operator would think to type. */
  public String searchText() {
    return (actor + " " + action + " " + verdict + " " + subject).toLowerCase(Locale.ROOT);
  }
}
