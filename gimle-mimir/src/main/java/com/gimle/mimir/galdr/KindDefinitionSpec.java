package com.gimle.mimir.galdr;

import java.util.List;

/**
 * One declared custom kind -- what a {@code kind: KindDefinition} manifest stores after admission.
 * {@code kindName} always carries its dot-separated prefix ({@code custom.Greeting}); an unprefixed
 * submission is normalized before it ever reaches the store, and built-in kinds never contain a
 * dot, so a future platform kind can never shadow a custom one. {@code generation} is bumped by the
 * store on every accepted put (never by the proposer) and read back as the compare-and-set
 * precondition for the next update, the same lineage discipline {@code DeploymentSpec} updates
 * follow.
 */
public record KindDefinitionSpec(
    String kindName,
    KindScope scope,
    String description,
    KindNames names,
    SchemaModel schema,
    List<PrintColumn> printColumns,
    long generation) {

  public KindDefinitionSpec {
    printColumns = List.copyOf(printColumns);
  }

  public KindDefinitionSpec withGeneration(final long newGeneration) {
    return new KindDefinitionSpec(
        kindName, scope, description, names, schema, printColumns, newGeneration);
  }
}
