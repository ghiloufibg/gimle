@holmgang @membership @destructive
Feature: Live store membership change
  The store cluster's membership changes while it serves: a new member joins
  through the real AddServer flow and leaves again through RemoveServer --
  etcd-style, one server at a time -- with writes accepted throughout, observed
  through the store's own status surface rather than inferred from timing.

  Scenario: A fourth store joins and then leaves, one server at a time
    Given a running cluster from topology "ha-plaintext"
    Then within 30s the store reports 3 members
    When a new store node joins the cluster
    Then within 60s the store reports 4 members
    And within 30s the cluster accepts writes again
    When the newest store leaves the cluster
    Then within 60s the store reports 3 members
    And within 30s the cluster accepts writes again
