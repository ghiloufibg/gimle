package com.gimle.mimir.galdr;

import java.util.List;

/**
 * A custom kind's declared spec shape: the top-level field list of the {@code spec:} block every
 * instance manifest carries. Stored as this parsed model inside {@link KindDefinitionSpec}, so
 * admission never re-parses schema YAML, and encoded field-by-field on the wire by {@code
 * DomainCodec} -- no per-kind wire format exists anywhere.
 */
public record SchemaModel(List<SchemaField> fields) {

  public SchemaModel {
    fields = List.copyOf(fields);
  }
}
