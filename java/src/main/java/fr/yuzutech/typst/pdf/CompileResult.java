package fr.yuzutech.typst.pdf;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of {@link TypstCompiler#compile}: a PDF or errors, plus warnings. */
public final class CompileResult {

  private final byte[] pdf;
  private final List<Diagnostic> diagnostics;

  CompileResult(byte[] pdf, List<Diagnostic> diagnostics) {
    this.pdf = pdf;
    this.diagnostics = List.copyOf(diagnostics);
  }

  /** Whether the document compiled; if so {@link #pdf()} returns it. */
  public boolean isSuccess() {
    return pdf != null;
  }

  /**
   * The PDF document.
   *
   * @throws TypstCompileException if the compilation failed
   */
  public byte[] pdf() {
    if (pdf == null) {
      throw new TypstCompileException(errors());
    }
    return pdf.clone();
  }

  /** All diagnostics: errors and warnings. */
  public List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  public List<Diagnostic> errors() {
    return filter(Diagnostic.Severity.ERROR);
  }

  public List<Diagnostic> warnings() {
    return filter(Diagnostic.Severity.WARNING);
  }

  private List<Diagnostic> filter(Diagnostic.Severity severity) {
    return diagnostics.stream()
        .filter(d -> d.severity() == severity)
        .collect(Collectors.toUnmodifiableList());
  }
}
