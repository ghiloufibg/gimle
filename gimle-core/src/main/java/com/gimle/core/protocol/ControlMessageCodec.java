package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Encodes/decodes a {@link ControlMessage} as one line of space-separated fields, message type
 * first. Free-text fields (currently {@code Nack#reason} and {@code InstallModule#artifactPath})
 * are escaped so an embedded space or newline can't corrupt line framing — the same trivial
 * escaping a line-oriented protocol like Redis's inline commands or SMTP status lines uses, not a
 * new dependency. Module names/versions never need escaping: both are validated elsewhere ({@code
 * ModuleDescriptorParser}, {@code Version#parse}) to a syntax that excludes spaces.
 */
public final class ControlMessageCodec {

  private ControlMessageCodec() {}

  public static String encode(ControlMessage message) {
    return switch (message) {
      case ControlMessage.Hello m ->
          line(
              "HELLO",
              m.workerId(),
              Long.toString(m.pid()),
              escape(m.fabricUdsPath()),
              escape(m.fabricTcpHost()),
              Integer.toString(m.fabricTcpPort()));
      case ControlMessage.Ack m -> line("ACK", m.correlationId());
      case ControlMessage.Nack m -> line("NACK", m.correlationId(), escape(m.reason()));
      case ControlMessage.ModuleStateChanged m -> line("MODULE_STATE", encodeId(m.id()), m.state());
      case ControlMessage.HealthReport m ->
          line(
              "HEALTH", encodeId(m.id()), Boolean.toString(m.alive()), Boolean.toString(m.ready()));
      case ControlMessage.MetricsReport m ->
          line(
              "METRICS",
              encodeId(m.id()),
              Long.toString(m.cpuMillicoresUsed()),
              Long.toString(m.memoryBytesUsed()),
              Double.toString(m.requestRatePerSecond()),
              Integer.toString(m.queueDepth()));
      case ControlMessage.ServiceRegistered m ->
          line("SERVICE_REGISTERED", encodeId(m.moduleId()), encodeExport(m.export()));
      case ControlMessage.ServiceUnregistered m ->
          line("SERVICE_UNREGISTERED", encodeId(m.moduleId()), encodeExport(m.export()));
      case ControlMessage.Pong m -> line("PONG", m.correlationId());
      case ControlMessage.InstallModule m ->
          line(
              "INSTALL",
              m.correlationId(),
              escape(m.artifactPath()),
              escape(m.deploymentName()),
              Integer.toString(m.instanceIndex()));
      case ControlMessage.ResolveModule m -> line("RESOLVE", m.correlationId(), encodeId(m.id()));
      case ControlMessage.StartModule m -> line("START", m.correlationId(), encodeId(m.id()));
      case ControlMessage.StopModule m -> line("STOP", m.correlationId(), encodeId(m.id()));
      case ControlMessage.UninstallModule m ->
          line("UNINSTALL", m.correlationId(), encodeId(m.id()));
      case ControlMessage.Ping m -> line("PING", m.correlationId());
      case ControlMessage.CatalogUpdate m ->
          line(
              "CATALOG_UPDATE",
              escape(m.nodeId()),
              escape(m.workerId()),
              encodeId(m.moduleId()),
              encodeExport(m.export()),
              Long.toString(m.version()),
              Boolean.toString(m.present()),
              escape(m.udsPath()),
              escape(m.tcpHost()),
              Integer.toString(m.tcpPort()));
      case ControlMessage.ConfigDelivered m ->
          line(
              "CONFIG_DELIVERED",
              escape(m.key()),
              escape(m.value()),
              Boolean.toString(m.wasEncrypted()));
    };
  }

  public static ControlMessage decode(String rawLine) {
    List<String> fields = List.of(rawLine.split(" ", -1));
    if (fields.isEmpty() || fields.get(0).isEmpty()) {
      throw new IllegalArgumentException("empty control message line");
    }
    String type = fields.get(0);
    return switch (type) {
      case "HELLO" ->
          new ControlMessage.Hello(
              field(fields, 1),
              Long.parseLong(field(fields, 2)),
              unescape(field(fields, 3)),
              unescape(field(fields, 4)),
              Integer.parseInt(field(fields, 5)));
      case "ACK" -> new ControlMessage.Ack(field(fields, 1));
      case "NACK" -> new ControlMessage.Nack(field(fields, 1), unescape(field(fields, 2)));
      case "MODULE_STATE" ->
          new ControlMessage.ModuleStateChanged(decodeId(field(fields, 1)), field(fields, 2));
      case "HEALTH" ->
          new ControlMessage.HealthReport(
              decodeId(field(fields, 1)),
              Boolean.parseBoolean(field(fields, 2)),
              Boolean.parseBoolean(field(fields, 3)));
      case "METRICS" ->
          new ControlMessage.MetricsReport(
              decodeId(field(fields, 1)),
              Long.parseLong(field(fields, 2)),
              Long.parseLong(field(fields, 3)),
              Double.parseDouble(field(fields, 4)),
              Integer.parseInt(field(fields, 5)));
      case "SERVICE_REGISTERED" ->
          new ControlMessage.ServiceRegistered(
              decodeId(field(fields, 1)), decodeExport(field(fields, 2)));
      case "SERVICE_UNREGISTERED" ->
          new ControlMessage.ServiceUnregistered(
              decodeId(field(fields, 1)), decodeExport(field(fields, 2)));
      case "PONG" -> new ControlMessage.Pong(field(fields, 1));
      case "INSTALL" ->
          new ControlMessage.InstallModule(
              field(fields, 1),
              unescape(field(fields, 2)),
              unescape(field(fields, 3)),
              Integer.parseInt(field(fields, 4)));
      case "RESOLVE" ->
          new ControlMessage.ResolveModule(field(fields, 1), decodeId(field(fields, 2)));
      case "START" -> new ControlMessage.StartModule(field(fields, 1), decodeId(field(fields, 2)));
      case "STOP" -> new ControlMessage.StopModule(field(fields, 1), decodeId(field(fields, 2)));
      case "UNINSTALL" ->
          new ControlMessage.UninstallModule(field(fields, 1), decodeId(field(fields, 2)));
      case "PING" -> new ControlMessage.Ping(field(fields, 1));
      case "CATALOG_UPDATE" ->
          new ControlMessage.CatalogUpdate(
              unescape(field(fields, 1)),
              unescape(field(fields, 2)),
              decodeId(field(fields, 3)),
              decodeExport(field(fields, 4)),
              Long.parseLong(field(fields, 5)),
              Boolean.parseBoolean(field(fields, 6)),
              unescape(field(fields, 7)),
              unescape(field(fields, 8)),
              Integer.parseInt(field(fields, 9)));
      case "CONFIG_DELIVERED" ->
          new ControlMessage.ConfigDelivered(
              unescape(field(fields, 1)),
              unescape(field(fields, 2)),
              Boolean.parseBoolean(field(fields, 3)));
      default -> throw new IllegalArgumentException("unknown control message type: " + type);
    };
  }

  private static String line(String type, String... fields) {
    StringBuilder text = new StringBuilder(type);
    for (String field : fields) {
      text.append(' ').append(field);
    }
    return text.toString();
  }

  private static String field(List<String> fields, int index) {
    if (index >= fields.size()) {
      throw new IllegalArgumentException("missing field at index " + index + " in: " + fields);
    }
    return fields.get(index);
  }

  private static String encodeId(ModuleId id) {
    return id.name() + "@" + id.version();
  }

  private static ModuleId decodeId(String text) {
    int at = text.lastIndexOf('@');
    if (at < 0) {
      throw new IllegalArgumentException("malformed module id on wire: " + text);
    }
    return new ModuleId(text.substring(0, at), Version.parse(text.substring(at + 1)));
  }

  /**
   * {@code @}-separated {@code interfaceName@version@tenantsField}, where {@code tenantsField} is
   * {@code *} for {@code allowedTenantIds().isEmpty()} ("any tenant") or a comma-joined escaped
   * list otherwise (possibly empty, meaning a present-but-empty set -- "no tenant may consume
   * this"). Safe to split without a general escaping scheme because neither a Java interface name
   * nor {@link Version#toString()} ever contains a literal {@code @} (the same assumption {@code
   * encodeId} already relies on for {@code ModuleId}).
   */
  private static String encodeExport(ServiceExport export) {
    String tenantsField =
        export.allowedTenantIds().isEmpty()
            ? "*"
            : export.allowedTenantIds().get().stream()
                .map(ControlMessageCodec::escape)
                .collect(Collectors.joining(","));
    return escape(export.interfaceName()) + "@" + export.version() + "@" + tenantsField;
  }

  private static ServiceExport decodeExport(String text) {
    String[] parts = text.split("@", 3);
    if (parts.length < 2) {
      throw new IllegalArgumentException("malformed service export on wire: " + text);
    }
    String interfaceName = unescape(parts[0]);
    Version version = Version.parse(parts[1]);
    if (parts.length < 3 || parts[2].equals("*")) {
      return new ServiceExport(interfaceName, version);
    }
    Set<String> tenants =
        parts[2].isEmpty()
            ? Set.of()
            : Arrays.stream(parts[2].split(","))
                .map(ControlMessageCodec::unescape)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    return new ServiceExport(interfaceName, version, Optional.of(tenants));
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace(" ", "\\s");
  }

  private static String unescape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' && i + 1 < text.length()) {
        char next = text.charAt(++i);
        switch (next) {
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 's' -> out.append(' ');
          case '\\' -> out.append('\\');
          default -> out.append(next);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
