@holmgang @chaos @destructive @registry-observability-ha
Feature: Muninn and Andvari replica resilience
  Muninn's shipping fans a batch out to every configured endpoint and Andvari's pushed
  artifacts peer-sync across every configured replica, but until Fenrir gained a bounce
  fault for each, none of that machinery had ever actually been chaos-tested. This soak
  strikes specifically those two fault kinds against a topology with two replicas of
  each, then proves both the registry and the observability path still work afterward.

  Scenario: Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces
    Given a running cluster from topology "registry-observability-ha"
    When module "greeter-provider" version "1.0.0" is pushed to the artifact registry
    And Fenrir is unleashed striking only muninn and andvari bounces for 30 seconds every 8 seconds
    Then the chaos ledger shows at least 2 executed faults
    And every executed fault recovered
    When module "greeter-provider" version "1.0.0" submitted from the registry with 1 replica as "registry-ha-provider"
    Then within 60s deployment "registry-ha-provider" is ACTIVE
    And within 30s the control plane's own request metrics are shipped to muninn
