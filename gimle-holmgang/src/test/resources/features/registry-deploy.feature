@holmgang @andvari
Feature: Artifact registry deployment
  A module jar pushed to the registry is deployable by coordinate alone: a manifest
  naming no artifactPath anywhere still reaches ACTIVE on a real worker JVM, because
  the control plane and the placing node agent each resolve the coordinate themselves.

  Scenario: A pushed module deploys by coordinate with no artifact path
    Given a running cluster from topology "registry"
    When module "greeter-provider" version "1.0.0" is pushed to the artifact registry
    And module "greeter-provider" version "1.0.0" submitted from the registry with 1 replica as "registry-provider"
    Then within 60s deployment "registry-provider" is ACTIVE

  Scenario: A v1 manifest deploys by coordinate through the registry
    Given a running cluster from topology "registry"
    When module "greeter-provider" version "1.0.0" is pushed to the artifact registry
    And a v1 coordinate-only deployment manifest for module "greeter-provider" version "1.0.0" is submitted as "v1-registry-provider"
    Then the manifest submission is accepted
    And within 60s deployment "v1-registry-provider" is ACTIVE
