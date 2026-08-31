package com.gimle.controlplane.alert;

/**
 * Delivers one {@link AlertNotification} somewhere outside the cluster -- the smallest notification
 * mechanism that doesn't assume any particular chat/paging vendor, matching {@code
 * AlertRuleSpec#webhookUrl()}'s own "plain HTTP POST" design. An interface, not a concrete class
 * directly, purely so {@link AlertReconciler}'s own tests can substitute a recording fake instead
 * of standing up a real HTTP server -- {@link WebhookAlertNotifier} is the only production
 * implementation.
 */
public interface AlertNotifier {

  void notify(AlertNotification notification);
}
