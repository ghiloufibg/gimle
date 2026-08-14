@holmgang @scheduling
Feature: Node cordoning
  A cordoned node accepts no new placements -- a submission just stays unplaced,
  it is never rejected and nothing already running is evicted -- and uncordoning
  lets the pending placement proceed.

  Scenario: A cordoned node blocks placement until uncordoned
    Given a running cluster from topology "minimal"
    When node "node-1" is cordoned
    And module "greeter-provider" version "1.0.0" submitted with 1 replica as "cordoned-greeter"
    Then deployment "cordoned-greeter" stays unplaced for 10s
    When node "node-1" is uncordoned
    Then within 120s deployment "cordoned-greeter" is ACTIVE
