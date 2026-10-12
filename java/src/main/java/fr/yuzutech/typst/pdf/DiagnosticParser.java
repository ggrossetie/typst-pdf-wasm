package fr.yuzutech.typst.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the diagnostics JSON produced by the Wasm module. Only supports what the module emits
 * (objects, arrays, strings and non-negative integers), to avoid a JSON dependency.
 */
final class DiagnosticParser {

  private final String json;
  private int pos;

  private DiagnosticParser(String json) {
    this.json = json;
  }

  static List<Diagnostic> parse(String json) {
    DiagnosticParser parser = new DiagnosticParser(json);
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (Object item : parser.array()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) item;
      @SuppressWarnings("unchecked")
      List<Object> hints = (List<Object>) map.getOrDefault("hints", List.of());
      List<String> hintStrings = new ArrayList<>();
      hints.forEach(h -> hintStrings.add((String) h));
      diagnostics.add(
          new Diagnostic(
              "error".equals(map.get("severity"))
                  ? Diagnostic.Severity.ERROR
                  : Diagnostic.Severity.WARNING,
              (String) map.get("message"),
              (String) map.get("path"),
              integer(map, "line", 0),
              integer(map, "column", 0),
              integer(map, "start", -1),
              integer(map, "end", -1),
              hintStrings));
    }
    return diagnostics;
  }

  private static int integer(Map<String, Object> map, String key, int fallback) {
    Object value = map.get(key);
    return value == null ? fallback : (int) (long) (Long) value;
  }

  private Object value() {
    skipWhitespace();
    char c = json.charAt(pos);
    switch (c) {
      case '[':
        return array();
      case '{':
        return object();
      case '"':
        return string();
      default:
        return number();
    }
  }

  private List<Object> array() {
    expect('[');
    List<Object> list = new ArrayList<>();
    skipWhitespace();
    if (json.charAt(pos) == ']') {
      pos++;
      return list;
    }
    while (true) {
      list.add(value());
      skipWhitespace();
      if (json.charAt(pos++) == ']') {
        return list;
      }
    }
  }

  private Map<String, Object> object() {
    expect('{');
    Map<String, Object> map = new HashMap<>();
    skipWhitespace();
    if (json.charAt(pos) == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = string();
      skipWhitespace();
      expect(':');
      map.put(key, value());
      skipWhitespace();
      if (json.charAt(pos++) == '}') {
        return map;
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = json.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char escape = json.charAt(pos++);
      switch (escape) {
        case 'n':
          sb.append('\n');
          break;
        case 'r':
          sb.append('\r');
          break;
        case 't':
          sb.append('\t');
          break;
        case 'u':
          sb.append((char) Integer.parseInt(json.substring(pos, pos + 4), 16));
          pos += 4;
          break;
        default:
          sb.append(escape);
      }
    }
  }

  private Long number() {
    int start = pos;
    while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
      pos++;
    }
    if (start == pos) {
      throw new IllegalStateException("Unexpected character at " + pos + " in " + json);
    }
    return Long.parseLong(json.substring(start, pos));
  }

  private void expect(char c) {
    skipWhitespace();
    if (json.charAt(pos) != c) {
      throw new IllegalStateException("Expected '" + c + "' at " + pos + " in " + json);
    }
    pos++;
  }

  private void skipWhitespace() {
    while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
      pos++;
    }
  }
}
