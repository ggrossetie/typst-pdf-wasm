package fr.yuzutech.typst.pdf;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown by {@link CompileResult#pdf()} when the document did not compile. */
public final class TypstCompileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Diagnostic> errors;

  TypstCompileException(List<Diagnostic> errors) {
    super(
        "Typst compilation failed:\n"
            + errors.stream().map(Diagnostic::toString).collect(Collectors.joining("\n")));
    this.errors = errors;
  }

  public List<Diagnostic> errors() {
    return errors;
  }
}
