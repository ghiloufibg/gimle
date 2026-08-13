package com.gimle.core.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw new IllegalArgumentException(
          "trailing content after JSON value at position " + parser.pos);
    }
    return value;
  }

  /**
   * Casts a parsed value to {@code Map<String, Object>}. The single unchecked cast this dynamic,
   * erasure-based JSON model requires lives here once, so callers reading a parsed object don't
   * each need their own {@code @SuppressWarnings("unchecked")}.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> asObject(Object value) {
    return (Map<String, Object>) value;
  }

  /** Same rationale as {@link #asObject(Object)}, for a JSON array of arbitrary values. */
  @SuppressWarnings("unchecked")
  public static List<Object> asArray(Object value) {
    return (List<Object>) value;
  }

  /** Same rationale as {@link #asObject(Object)}, for a JSON array of objects. */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> asObjectList(Object value) {
    return (List<Map<String, Object>>) (List<?>) value;
  }

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(value, sb);
    return sb.toString();
  }

  /**
   * Writes one JSON line per element of {@code items}, each terminated with a newline -- the shared
   * shape every newline-delimited-JSON batch producer in the codebase otherwise builds by hand with
   * its own {@code StringBuilder} loop. Empty for an empty (or null-mapping-to-nothing) collection,
   * matching every caller's own "nothing to ship" convention.
   */
  public static <T> String writeNdjson(Collection<T> items, Function<T, Object> toJsonValue) {
    StringBuilder body = new StringBuilder();
    for (T item : items) {
      body.append(write(toJsonValue.apply(item))).append('\n');
    }
    return body.toString();
  }

  private static void writeValue(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeString(s, sb);
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
        writeString(String.valueOf(entry.getKey()), sb);
        sb.append(':');
        writeValue(entry.getValue(), sb);
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
        writeValue(item, sb);
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("cannot serialize value of type " + value.getClass());
    }
  }

  private static void writeString(String s, StringBuilder sb) {
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

    boolean atEnd() {
      return pos >= text.length();
    }

    void skipWhitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }

    Object parseValue() {
      skipWhitespace();
      if (atEnd()) {
        throw new IllegalArgumentException("unexpected end of JSON input");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't', 'f' -> parseBoolean();
        case 'n' -> parseNull();
        default -> parseNumber();
      };
    }

    Map<String, Object> parseObject() {
      expect('{');
      Map<String, Object> result = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return result;
      }
      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        Object value = parseValue();
        result.put(key, value);
        skipWhitespace();
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

    List<Object> parseArray() {
      expect('[');
      List<Object> result = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return result;
      }
      while (true) {
        Object value = parseValue();
        result.add(value);
        skipWhitespace();
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

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (atEnd()) {
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

    Boolean parseBoolean() {
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

    Object parseNull() {
      if (text.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      throw new IllegalArgumentException("invalid literal at position " + pos);
    }

    Number parseNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      while (!atEnd() && Character.isDigit(text.charAt(pos))) {
        pos++;
      }
      boolean isDouble = false;
      if (!atEnd() && text.charAt(pos) == '.') {
        isDouble = true;
        pos++;
        while (!atEnd() && Character.isDigit(text.charAt(pos))) {
          pos++;
        }
      }
      if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
        isDouble = true;
        pos++;
        if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
          pos++;
        }
        while (!atEnd() && Character.isDigit(text.charAt(pos))) {
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
      if (atEnd()) {
        throw new IllegalArgumentException("unexpected end of JSON input");
      }
      return text.charAt(pos);
    }

    void expect(char expected) {
      if (atEnd() || text.charAt(pos) != expected) {
        throw new IllegalArgumentException("expected '" + expected + "' at position " + pos);
      }
      pos++;
    }
  }
}
