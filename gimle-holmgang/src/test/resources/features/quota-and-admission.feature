@holmgang @quota
Feature: Tenant quotas and admission control
  A quota lowered below what a tenant already runs surfaces as a violation flag
  without ever evicting the running instance; a new deployment that would exceed
  its tenant's quota is rejected outright at admission.

  Scenario: A retroactive quota violation is flagged but never evicts
    Given a running cluster from topology "minimal"
    And a tenant "quota-tenant" with quota 268435456 bytes memory, 1000 millicores and 10 instances
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "quota-greeter" for tenant "quota-tenant"
    When tenant "quota-tenant" quota is lowered to 1 bytes memory, 1 millicores and 1 instances
    Then within 60s deployment "quota-greeter" reports a quota violation
    And deployment "quota-greeter" keeps 1 ACTIVE instance for 10s

  Scenario: An over-quota deployment is rejected at admission
    Given a running cluster from topology "minimal"
    And a tenant "starved-tenant" with quota 1 bytes memory, 1 millicores and 1 instances
    When deploying module "greeter-provider" version "1.0.0" with 1 replica as "rejected-greeter" for tenant "starved-tenant" is attempted
    Then the submission is rejected with status 409
