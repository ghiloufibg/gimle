package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.List;

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
      case ControlMessage.Hello m -> line("HELLO", m.workerId(), Long.toString(m.pid()));
      case ControlMessage.Ack m -> line("ACK", m.correlationId());
      case ControlMessage.Nack m -> line("NACK", m.correlationId(), escape(m.reason()));
      case ControlMessage.ModuleStateChanged m ->
          line("MODULE_STATE", encode_id(m.id()), m.state());
      case ControlMessage.HealthReport m ->
          line(
              "HEALTH",
              encode_id(m.id()),
              Boolean.toString(m.alive()),
              Boolean.toString(m.ready()));
      case ControlMessage.MetricsReport m ->
          line(
              "METRICS",
              encode_id(m.id()),
              Long.toString(m.cpuMillicoresUsed()),
              Long.toString(m.memoryBytesUsed()));
      case ControlMessage.Pong m -> line("PONG", m.correlationId());
      case ControlMessage.InstallModule m ->
          line("INSTALL", m.correlationId(), escape(m.artifactPath()));
      case ControlMessage.ResolveModule m -> line("RESOLVE", m.correlationId(), encode_id(m.id()));
      case ControlMessage.StartModule m -> line("START", m.correlationId(), encode_id(m.id()));
      case ControlMessage.StopModule m -> line("STOP", m.correlationId(), encode_id(m.id()));
      case ControlMessage.UninstallModule m ->
          line("UNINSTALL", m.correlationId(), encode_id(m.id()));
      case ControlMessage.Ping m -> line("PING", m.correlationId());
    };
  }

  public static ControlMessage decode(String rawLine) {
    List<String> fields = List.of(rawLine.split(" ", -1));
    if (fields.isEmpty() || fields.get(0).isEmpty()) {
      throw new IllegalArgumentException("empty control message line");
    }
    String type = fields.get(0);
    return switch (type) {
      case "HELLO" -> new ControlMessage.Hello(field(fields, 1), Long.parseLong(field(fields, 2)));
      case "ACK" -> new ControlMessage.Ack(field(fields, 1));
      case "NACK" -> new ControlMessage.Nack(field(fields, 1), unescape(field(fields, 2)));
      case "MODULE_STATE" ->
          new ControlMessage.ModuleStateChanged(decode_id(field(fields, 1)), field(fields, 2));
      case "HEALTH" ->
          new ControlMessage.HealthReport(
              decode_id(field(fields, 1)),
              Boolean.parseBoolean(field(fields, 2)),
              Boolean.parseBoolean(field(fields, 3)));
      case "METRICS" ->
          new ControlMessage.MetricsReport(
              decode_id(field(fields, 1)),
              Long.parseLong(field(fields, 2)),
              Long.parseLong(field(fields, 3)));
      case "PONG" -> new ControlMessage.Pong(field(fields, 1));
      case "INSTALL" ->
          new ControlMessage.InstallModule(field(fields, 1), unescape(field(fields, 2)));
      case "RESOLVE" ->
          new ControlMessage.ResolveModule(field(fields, 1), decode_id(field(fields, 2)));
      case "START" -> new ControlMessage.StartModule(field(fields, 1), decode_id(field(fields, 2)));
      case "STOP" -> new ControlMessage.StopModule(field(fields, 1), decode_id(field(fields, 2)));
      case "UNINSTALL" ->
          new ControlMessage.UninstallModule(field(fields, 1), decode_id(field(fields, 2)));
      case "PING" -> new ControlMessage.Ping(field(fields, 1));
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

  private static String encode_id(ModuleId id) {
    return id.name() + "@" + id.version();
  }

  private static ModuleId decode_id(String text) {
    int at = text.lastIndexOf('@');
    if (at < 0) {
      throw new IllegalArgumentException("malformed module id on wire: " + text);
    }
    return new ModuleId(text.substring(0, at), Version.parse(text.substring(at + 1)));
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
