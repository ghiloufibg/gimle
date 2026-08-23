@holmgang @limitrange
Feature: Tenant LimitRanges
  A LimitRange bounds what a single deployment may declare for its own
  resources.request/resources.limit, distinct from a tenant's aggregate
  ResourceQuota: a submission that would violate the tenant's LimitRange is
  rejected outright at admission, and a range retroactively tightened below
  what a deployment already declares surfaces as a violation flag without
  ever evicting the running instance.

  Scenario: An over-range deployment is rejected at admission
    Given a running cluster from topology "minimal"
    And a tenant "limitrange-tenant" with quota 1000000000 bytes memory, 4000 millicores and 10 instances
    And a limitrange for tenant "limitrange-tenant" with max request memory "1Mi" and cpu "1m"
    When deploying module "greeter-provider" version "1.0.0" with 1 replica as "rejected-greeter" for tenant "limitrange-tenant" is attempted
    Then the submission is rejected with status 409

  Scenario: A retroactively tightened LimitRange is flagged but never evicts
    Given a running cluster from topology "minimal"
    And a tenant "limitrange-tenant-2" with quota 1000000000 bytes memory, 4000 millicores and 10 instances
    And a limitrange for tenant "limitrange-tenant-2" with max request memory "1000Mi" and cpu "4000m"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "limitrange-greeter" for tenant "limitrange-tenant-2"
    When limitrange for tenant "limitrange-tenant-2" is tightened to max request memory "1Mi" and cpu "1m"
    Then within 60s deployment "limitrange-greeter" reports a limit range violation
    And deployment "limitrange-greeter" keeps 1 ACTIVE instance for 10s
