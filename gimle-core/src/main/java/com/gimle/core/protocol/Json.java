package com.gimle.core.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal hand-rolled JSON reader/writer for the control plane's fixed, fully-known-up-front HTTP
 * request/response shapes -- the same "hand-roll it, it's small" posture {@link
 * ControlMessageCodec} already used for the agent&harr;worker channel, applied here because this
 * schema isn't user-extensible either. Lives in {@code gimle-core} (not the control plane) because
 * both the control plane (server) and the agent (client) need to speak it, the same reasoning
 * {@link ControlMessage} itself is here rather than duplicated per side. Reads/writes plain {@code
 * Map}/{@code List}/{@code String}/{@code Number}/{@code Boolean}/{@code null}, not typed records
 * directly -- callers map to/from their own record types themselves.
 */
public final class Json {

  private Json() {}

  public static Object parse(String text) {
    Parser parser = new Parser(text);
    Object value = parser.parse_value();
    parser.skip_whitespace();
    if (!parser.at_end()) {
      throw new IllegalArgumentException(
          "trailing content after JSON value at position " + parser.pos);
    }
    return value;
  }

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write_value(value, sb);
    return sb.toString();
  }

  private static void write_value(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      write_string(s, sb);
    } else if (value instanceof Boolean b) {
      sb.append(b);
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write_string(String.valueOf(entry.getKey()), sb);
        sb.append(':');
        write_value(entry.getValue(), sb);
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      sb.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write_value(item, sb);
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("cannot serialize value of type " + value.getClass());
    }
  }

  private static void write_string(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    boolean at_end() {
      return pos >= text.length();
    }

    void skip_whitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }

    Object parse_value() {
      skip_whitespace();
      if (at_end()) {
        throw new IllegalArgumentException("unexpected end of JSON input");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> parse_object();
        case '[' -> parse_array();
        case '"' -> parse_string();
        case 't', 'f' -> parse_boolean();
        case 'n' -> parse_null();
        default -> parse_number();
      };
    }

    Map<String, Object> parse_object() {
      expect('{');
      Map<String, Object> result = new LinkedHashMap<>();
      skip_whitespace();
      if (peek() == '}') {
        pos++;
        return result;
      }
      while (true) {
        skip_whitespace();
        String key = parse_string();
        skip_whitespace();
        expect(':');
        Object value = parse_value();
        result.put(key, value);
        skip_whitespace();
        char next = peek();
        if (next == ',') {
          pos++;
          continue;
        }
        if (next == '}') {
          pos++;
          break;
        }
        throw new IllegalArgumentException("expected ',' or '}' at position " + pos);
      }
      return result;
    }

    List<Object> parse_array() {
      expect('[');
      List<Object> result = new ArrayList<>();
      skip_whitespace();
      if (peek() == ']') {
        pos++;
        return result;
      }
      while (true) {
        Object value = parse_value();
        result.add(value);
        skip_whitespace();
        char next = peek();
        if (next == ',') {
          pos++;
          continue;
        }
        if (next == ']') {
          pos++;
          break;
        }
        throw new IllegalArgumentException("expected ',' or ']' at position " + pos);
      }
      return result;
    }

    String parse_string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (at_end()) {
          throw new IllegalArgumentException("unterminated string");
        }
        char c = text.charAt(pos++);
        if (c == '"') {
          break;
        }
        if (c == '\\') {
          char escaped = text.charAt(pos++);
          switch (escaped) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              String hex = text.substring(pos, pos + 4);
              sb.append((char) Integer.parseInt(hex, 16));
              pos += 4;
            }
            default -> throw new IllegalArgumentException("invalid escape sequence: \\" + escaped);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    Boolean parse_boolean() {
      if (text.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return Boolean.FALSE;
      }
      throw new IllegalArgumentException("invalid literal at position " + pos);
    }

    Object parse_null() {
      if (text.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      throw new IllegalArgumentException("invalid literal at position " + pos);
    }

    Number parse_number() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      while (!at_end() && Character.isDigit(text.charAt(pos))) {
        pos++;
      }
      boolean isDouble = false;
      if (!at_end() && text.charAt(pos) == '.') {
        isDouble = true;
        pos++;
        while (!at_end() && Character.isDigit(text.charAt(pos))) {
          pos++;
        }
      }
      if (!at_end() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
        isDouble = true;
        pos++;
        if (!at_end() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
          pos++;
        }
        while (!at_end() && Character.isDigit(text.charAt(pos))) {
          pos++;
        }
      }
      String token = text.substring(start, pos);
      if (token.isEmpty() || token.equals("-")) {
        throw new IllegalArgumentException("invalid number at position " + start);
      }
      return isDouble ? (Number) Double.parseDouble(token) : (Number) Long.parseLong(token);
    }

    char peek() {
      if (at_end()) {
        throw new IllegalArgumentException("unexpected end of JSON input");
      }
      return text.charAt(pos);
    }

    void expect(char expected) {
      if (at_end() || text.charAt(pos) != expected) {
        throw new IllegalArgumentException("expected '" + expected + "' at position " + pos);
      }
      pos++;
    }
  }
}
