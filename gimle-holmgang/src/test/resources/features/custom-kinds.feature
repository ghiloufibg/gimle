@holmgang @galdr
Feature: Custom kinds (Galdr)
  A KindDefinition manifest teaches a live cluster a new resource kind: instances are
  schema-validated at admission, a real hosted operator module reconciles them through
  its workload-identity relay and reports status back, and everything -- definition,
  spec, and status -- survives a control-plane bounce because it lives in the store,
  not in the control plane.

  Scenario: A hosted operator reconciles a defined kind's instances, across a control-plane bounce
    Given a running cluster from topology "minimal"
    When a Greeting kind definition is applied
    Then the manifest submission is accepted
    And the kind catalog lists "custom.Greeting"
    When Greeting "hello-world" for tenant "holmgang-tenant" is applied with message "hello" repeated 3 times
    And module "greeting-operator" version "1.0.0" deployed with 1 replica as "greeting-operator" for tenant "holmgang-tenant"
    Then within 60s Greeting "hello-world" for tenant "holmgang-tenant" reports timesSaid 3 caught up with its spec
    When control plane 0 is restarted
    Then within 30s the control plane is serving
    And Greeting "hello-world" for tenant "holmgang-tenant" still reports timesSaid 3
    When Greeting "hello-world" for tenant "holmgang-tenant" is applied with message "hello" repeated 5 times
    Then within 60s Greeting "hello-world" for tenant "holmgang-tenant" reports timesSaid 5 caught up with its spec

  Scenario: Admission validates instances against the declared schema and rejects loudly
    Given a running cluster from topology "minimal"
    When a Greeting kind definition is applied
    Then the manifest submission is accepted
    When a Greeting manifest with an unknown spec field is submitted for tenant "holmgang-tenant"
    Then the submission is rejected with status 400
    When a Greeting manifest with repeat 0 is submitted for tenant "holmgang-tenant"
    Then the submission is rejected with status 400
    When a Greeting manifest without a tenant is submitted
    Then the submission is rejected with status 400

  Scenario: Defaults are persisted and an identical re-apply never bumps the generation
    Given a running cluster from topology "minimal"
    When a Greeting kind definition is applied
    Then the manifest submission is accepted
    When Greeting "steady" for tenant "holmgang-tenant" is applied with message "hi" repeated 2 times
    Then Greeting "steady" for tenant "holmgang-tenant" has tone "friendly"
    And Greeting "steady" for tenant "holmgang-tenant" has generation 1
    When Greeting "steady" for tenant "holmgang-tenant" is applied with message "hi" repeated 2 times
    Then Greeting "steady" for tenant "holmgang-tenant" has generation 1
    When Greeting "steady" for tenant "holmgang-tenant" is applied with message "hi" repeated 4 times
    Then Greeting "steady" for tenant "holmgang-tenant" has generation 2
