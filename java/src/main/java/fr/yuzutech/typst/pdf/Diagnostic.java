package fr.yuzutech.typst.pdf;

import java.util.List;
import java.util.Optional;

/** An error or warning reported by Typst. */
public final class Diagnostic {

  public enum Severity {
    ERROR,
    WARNING
  }

  private final Severity severity;
  private final String message;
  private final String path;
  private final int line;
  private final int column;
  private final int start;
  private final int end;
  private final List<String> hints;

  Diagnostic(
      Severity severity,
      String message,
      String path,
      int line,
      int column,
      int start,
      int end,
      List<String> hints) {
    this.severity = severity;
    this.message = message;
    this.path = path;
    this.line = line;
    this.column = column;
    this.start = start;
    this.end = end;
    this.hints = List.copyOf(hints);
  }

  public Severity severity() {
    return severity;
  }

  public String message() {
    return message;
  }

  /** The project file the diagnostic points into, if any. */
  public Optional<String> path() {
    return Optional.ofNullable(path);
  }

  /** 1-based line, or 0 if unknown. */
  public int line() {
    return line;
  }

  /** 1-based column in characters, or 0 if unknown. */
  public int column() {
    return column;
  }

  /** Start byte offset in the file, or -1 if unknown. */
  public int start() {
    return start;
  }

  /** End byte offset (exclusive) in the file, or -1 if unknown. */
  public int end() {
    return end;
  }

  public List<String> hints() {
    return hints;
  }

  /** Formats as {@code error: /main.typ:3:5: message}, followed by hints. */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(severity == Severity.ERROR ? "error: " : "warning: ");
    if (path != null) {
      sb.append(path);
      if (line > 0) {
        sb.append(':').append(line).append(':').append(column);
      }
      sb.append(": ");
    }
    sb.append(message);
    for (String hint : hints) {
      sb.append("\n  hint: ").append(hint);
    }
    return sb.toString();
  }
}
