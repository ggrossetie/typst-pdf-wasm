package fr.yuzutech.typst.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypstCompilerTest {

  private static TypstCompiler compiler;

  @BeforeAll
  static void createCompiler() {
    compiler = TypstCompiler.create();
  }

  @BeforeEach
  void resetCompiler() {
    compiler.reset();
  }

  @Test
  void compilesToPdf() {
    compiler.addFile("/main.typ", "= Hello\n\nWorld, $x^2 + y^2 = z^2$");
    CompileResult result = compiler.compile();

    assertTrue(result.isSuccess(), result.diagnostics()::toString);
    assertTrue(startsWithPdfHeader(result.pdf()));
  }

  @Test
  void reportsErrorsWithLocation() {
    compiler.addFile("/main.typ", "Hello\n#unknown-function()");
    CompileResult result = compiler.compile();

    assertFalse(result.isSuccess());
    List<Diagnostic> errors = result.errors();
    assertEquals(1, errors.size());
    Diagnostic error = errors.get(0);
    assertEquals("unknown variable: unknown-function", error.message());
    assertEquals("/main.typ", error.path().orElseThrow());
    assertEquals(2, error.line());
    assertEquals(2, error.column());
    TypstCompileException e = assertThrows(TypstCompileException.class, result::pdf);
    assertTrue(e.getMessage().contains("/main.typ:2:2"), e::getMessage);
  }

  @Test
  void resolvesImportsAndBinaryFiles() {
    String svg =
        "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'>"
            + "<rect width='10' height='10'/></svg>";
    compiler
        .addFile("/lib/title.typ", "#let title(body) = heading(body)")
        .addFile("/images/square.svg", svg.getBytes(StandardCharsets.UTF_8))
        .addFile("/main.typ", "#import \"lib/title.typ\": title\n#title[Doc]\n#image(\"images/square.svg\")");

    CompileResult result = compiler.compile();

    assertTrue(result.isSuccess(), result.diagnostics()::toString);
  }

  @Test
  void exposesInputs() {
    compiler.setInput("name", "Typst");
    compiler.addFile("/main.typ", "#assert.eq(sys.inputs.name, \"Typst\")");

    assertTrue(compiler.compile().isSuccess());
  }

  @Test
  void usesHostTime() {
    compiler.addFile("/main.typ", "#assert.eq(datetime.today().year(), 2024)");
    assertFalse(compiler.compile().isSuccess());

    compiler.setNow(Instant.parse("2024-12-31T23:30:00Z"), ZoneOffset.UTC);
    assertTrue(compiler.compile().isSuccess());

    compiler.setNow(Instant.parse("2024-12-31T23:30:00Z"), ZoneOffset.ofHours(2));
    assertFalse(compiler.compile().isSuccess());
  }

  @Test
  void compilesAnotherMainFile() {
    compiler.addFile("/docs/report.typ", "Report");

    assertTrue(compiler.compile("/docs/report.typ").isSuccess());
    assertThrows(TypstCompileException.class, () -> compiler.compile().pdf());
  }

  @Test
  void reportsWarnings() {
    compiler.addFile("/main.typ", "#set text(font: \"No Such Font\")\nHello");
    CompileResult result = compiler.compile();

    assertTrue(result.isSuccess());
    assertFalse(result.warnings().isEmpty());
  }

  @Test
  void rejectsNonFonts() {
    assertEquals(0, compiler.addFont(new byte[] {1, 2, 3}));
  }

  private static boolean startsWithPdfHeader(byte[] pdf) {
    return new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
  }
}
