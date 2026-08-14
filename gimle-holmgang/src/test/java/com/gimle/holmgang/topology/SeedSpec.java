package com.gimle.holmgang.topology;

import java.util.List;

/**
 * Substrate state provisioned right after the control planes start serving, before any scenario
 * touches the cluster -- only the accounts and tenants nearly every scenario needs. Deployed
 * modules are deliberately not part of a topology: deployments are scenario actions.
 */
public record SeedSpec(List<AccountSeed> accounts, List<TenantSeed> tenants) {

  public static final SeedSpec EMPTY = new SeedSpec(List.of(), List.of());

  public SeedSpec {
    accounts = List.copyOf(accounts);
    tenants = List.copyOf(tenants);
  }
}
