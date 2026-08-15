package com.gimle.holmgang.topology;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Java entry point for defining a topology in code -- produces the exact same {@link
 * ClusterSpec} a YAML document would, so a test can define a one-off shape inline without a
 * resource file. Every validation rule lives in {@link ClusterSpec}'s own constructor, not here.
 */
public final class ClusterTopology {

  private ClusterTopology() {}

  public static Builder named(final String name) {
    return new Builder(name);
  }

  public static final class Builder {

    private final String name;
    private Transport transport = Transport.PLAINTEXT;
    private int storeReplicas = 1;
    private int controlPlaneReplicas = 1;
    private int fafnirReplicas = 1;
    private boolean muninnEnabled;
    private int muninnReplicas = 1;
    private boolean andvariEnabled;
    private int andvariReplicas = 1;
    private final List<NodeSpec> nodes = new ArrayList<>();
    private boolean faultsProxied;
    private final Map<ProcessRole, List<String>> extraJvmFlags = new EnumMap<>(ProcessRole.class);
    private final List<AccountSeed> seedAccounts = new ArrayList<>();
    private final List<TenantSeed> seedTenants = new ArrayList<>();

    private Builder(final String name) {
      this.name = name;
    }

    public Builder transport(final Transport value) {
      this.transport = value;
      return this;
    }

    public Builder stores(final int replicas) {
      this.storeReplicas = replicas;
      return this;
    }

    public Builder controlPlanes(final int replicas) {
      this.controlPlaneReplicas = replicas;
      return this;
    }

    public Builder fafnirs(final int replicas) {
      this.fafnirReplicas = replicas;
      return this;
    }

    public Builder muninn() {
      this.muninnEnabled = true;
      return this;
    }

    /** Enables Muninn with {@code replicas} fan-out-shipped-to instances instead of just one. */
    public Builder muninn(final int replicas) {
      this.muninnEnabled = true;
      this.muninnReplicas = replicas;
      return this;
    }

    public Builder andvari() {
      this.andvariEnabled = true;
      return this;
    }

    /** Enables Andvari with {@code replicas} peer-syncing instances instead of just one. */
    public Builder andvari(final int replicas) {
      this.andvariEnabled = true;
      this.andvariReplicas = replicas;
      return this;
    }

    /** Adds {@code count} label-less nodes named {@code node-1..node-count}. */
    public Builder nodes(final int count) {
      for (int i = 1; i <= count; i++) {
        node("node-" + i);
      }
      return this;
    }

    public Builder node(final String id, final String... labels) {
      nodes.add(new NodeSpec(id, List.of(labels)));
      return this;
    }

    public Builder faultsProxied() {
      this.faultsProxied = true;
      return this;
    }

    public Builder jvmFlags(final ProcessRole role, final String... flags) {
      extraJvmFlags.put(role, List.of(flags));
      return this;
    }

    public Builder seedAccount(final String username, final String password) {
      seedAccounts.add(new AccountSeed(username, password));
      return this;
    }

    public Builder seedTenant(final String tenantId, final QuotaSpec quota) {
      seedTenants.add(new TenantSeed(tenantId, quota));
      return this;
    }

    public ClusterSpec build() {
      return new ClusterSpec(
          name,
          transport,
          storeReplicas,
          controlPlaneReplicas,
          fafnirReplicas,
          muninnEnabled,
          muninnReplicas,
          andvariEnabled,
          andvariReplicas,
          nodes,
          faultsProxied,
          extraJvmFlags,
          new SeedSpec(seedAccounts, seedTenants));
    }
  }
}
