package com.gimle.controlplane.galdr;

import com.gimle.core.protocol.Json;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.galdr.KindScope;
import com.gimle.mimir.galdr.PrintColumn;
import com.gimle.mimir.galdr.SchemaField;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON renderings of the Galdr types for the {@code /kinddefinitions} and {@code /resources/*} read
 * surfaces, plus the canonicalization admission persists: a validated-and-defaulted spec tree
 * serialized in schema declaration order via {@link Json#write} is byte-deterministic, which is
 * what makes "identical re-apply is a no-op" a plain byte comparison rather than a structural one.
 */
public final class GaldrJson {

  private GaldrJson() {}

  /** The canonical bytes admission stores -- deterministic for a given defaulted tree. */
  public static byte[] canonicalJson(Map<String, Object> defaultedTree) {
    return Json.write(defaultedTree).getBytes(StandardCharsets.UTF_8);
  }

  public static Map<String, Object> definitionToJson(KindDefinitionSpec definition) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("kindName", definition.kindName());
    map.put("scope", definition.scope() == KindScope.TENANT ? "Tenant" : "Cluster");
    map.put("description", definition.description());
    Map<String, Object> names = new LinkedHashMap<>();
    definition.names().plural().ifPresent(plural -> names.put("plural", plural));
    names.put("shortNames", definition.names().shortNames());
    map.put("names", names);
    List<Map<String, Object>> fields = new ArrayList<>();
    for (SchemaField field : definition.schema().fields()) {
      fields.add(schemaFieldToJson(field));
    }
    map.put("schema", Map.of("fields", fields));
    List<Map<String, Object>> printColumns = new ArrayList<>();
    for (PrintColumn column : definition.printColumns()) {
      Map<String, Object> columnMap = new LinkedHashMap<>();
      columnMap.put("name", column.name());
      columnMap.put("path", column.path());
      printColumns.add(columnMap);
    }
    map.put("printColumns", printColumns);
    map.put("generation", definition.generation());
    return map;
  }

  private static Map<String, Object> schemaFieldToJson(SchemaField field) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", field.name());
    switch (field) {
      case SchemaField.StringField f -> {
        map.put("type", "string");
        map.put("required", f.required());
        f.defaultValue().ifPresent(v -> map.put("default", v));
        f.maxLength().ifPresent(v -> map.put("maxLength", v));
      }
      case SchemaField.IntField f -> {
        map.put("type", "int");
        map.put("required", f.required());
        f.defaultValue().ifPresent(v -> map.put("default", v));
        f.min().ifPresent(v -> map.put("min", v));
        f.max().ifPresent(v -> map.put("max", v));
      }
      case SchemaField.DoubleField f -> {
        map.put("type", "double");
        map.put("required", f.required());
        f.defaultValue().ifPresent(v -> map.put("default", v));
        f.min().ifPresent(v -> map.put("min", v));
        f.max().ifPresent(v -> map.put("max", v));
      }
      case SchemaField.BoolField f -> {
        map.put("type", "bool");
        map.put("required", f.required());
        f.defaultValue().ifPresent(v -> map.put("default", v));
      }
      case SchemaField.EnumField f -> {
        map.put("type", "enum");
        map.put("required", f.required());
        f.defaultValue().ifPresent(v -> map.put("default", v));
        map.put("values", f.values());
      }
      case SchemaField.ListField f -> {
        map.put("type", "list");
        map.put("items", schemaFieldToJson(f.items()));
        f.minItems().ifPresent(v -> map.put("minItems", v));
        f.maxItems().ifPresent(v -> map.put("maxItems", v));
      }
      case SchemaField.ObjectField f -> {
        map.put("type", "object");
        List<Map<String, Object>> nested = new ArrayList<>();
        for (SchemaField child : f.fields()) {
          nested.add(schemaFieldToJson(child));
        }
        map.put("fields", nested);
      }
    }
    return map;
  }

  /**
   * One instance with spec and status side by side -- {@code status} is {@code null} until an
   * operator first reports one, never a fabricated empty object.
   */
  public static Map<String, Object> resourceToJson(CustomResource resource) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("kind", resource.kindName());
    map.put("name", resource.name());
    resource.tenantId().ifPresent(tenant -> map.put("tenantId", tenant));
    map.put("generation", resource.generation());
    map.put("spec", Json.parse(new String(resource.specJson(), StandardCharsets.UTF_8)));
    byte[] status = resource.statusJson();
    map.put(
        "status",
        status.length == 0 ? null : Json.parse(new String(status, StandardCharsets.UTF_8)));
    return map;
  }
}
