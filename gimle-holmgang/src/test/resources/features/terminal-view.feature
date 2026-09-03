@holmgang @terminal-view
Feature: The terminal cluster view reads a real cluster
  `gimle top` renders a live, read-only view of a cluster by consuming the same
  control-plane routes every other client uses. Its rendering is a pure function of
  a snapshot and a viewport, so these scenarios drive the real readers and screens
  against a really running cluster and assert on the frames they produce -- no
  terminal, no keystrokes, no pseudo-terminal. What no scenario here reaches is the
  JLine adapter that puts a real terminal into raw mode; that is the reason it is
  kept as thin as it is.

  Scenario: A running deployment appears in the rendered frame with its real state
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "top-greeter"
    When the terminal view is rendered
    Then the terminal view shows a line containing "top-greeter"
    And the terminal view shows a line containing "ACTIVE"
    And the terminal view shows a line containing "node-1"
    And every terminal view line fits the terminal width

  Scenario: A workload the scheduler cannot place is reported rather than silently short
    Given a running cluster from topology "minimal"
    When node "node-1" is cordoned
    And module "greeter-provider" version "1.0.0" submitted with 2 replicas as "top-unplaced"
    Then deployment "top-unplaced" stays unplaced for 10s
    When the terminal view is rendered
    Then the terminal view shows a line containing "NOT SETTLED"
    And the terminal view shows a line containing "top-unplaced"
    And the terminal view shows a line containing "0 of 2 placed"
    When node "node-1" is uncordoned

  Scenario: A healthy cluster reports nothing unsettled
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "top-settled"
    When the terminal view is rendered
    Then the terminal view shows no line containing "NOT SETTLED"

  Scenario: A Service resolving to no endpoints is reported as the finding it is
    Given a running cluster from topology "minimal"
    When a Service "top-orphan" is declared fronting deployment "nothing-runs-here" on port 8080 targeting port 8080
    And the terminal view's services screen is rendered
    Then the terminal view shows a line containing "top-orphan"
    And the terminal view shows a line containing "NO ENDPOINTS"

  Scenario: A Service backed by a running deployment resolves an endpoint
    Given a running cluster from topology "minimal"
    And a module reporting its own port is deployed as "top-ported"
    When a Service "top-backed" is declared fronting deployment "top-ported" on port 8080 targeting port 8080
    Then within 60s Service "top-backed" resolves a live endpoint
    When the terminal view's services screen is rendered
    Then the terminal view shows a line containing "top-backed"
    And the terminal view shows no line containing "NO ENDPOINTS"
