package com.gimle.controlplane.alert;

import com.gimle.mimir.manifest.AlertRuleSpec;

/**
 * One state transition {@link AlertReconciler} detected for {@code rule}: {@code observedValue} (a
 * live reading of {@code rule.metric()}) just crossed {@code rule.threshold()} ({@link
 * State#FIRING}), or a previously-firing rule's reading has moved back to the safe side of it
 * ({@link State#RESOLVED}). Never sent more than once per transition -- {@link AlertReconciler}
 * tracks each rule's last-known state itself and only calls {@link AlertNotifier#notify} on an
 * actual edge, not on every tick a rule happens to still be crossed.
 */
public record AlertNotification(AlertRuleSpec rule, double observedValue, State state) {

  public enum State {
    FIRING,
    RESOLVED
  }
}
