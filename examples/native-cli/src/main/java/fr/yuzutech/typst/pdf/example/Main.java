package fr.yuzutech.typst.pdf.example;

import fr.yuzutech.typst.pdf.CompileResult;
import fr.yuzutech.typst.pdf.TypstCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Compiles a Typst file to PDF.
 *
 * <p>Usage: {@code typst-pdf <main.typ> <out.pdf> [key=value...]}. Every file in the directory of
 * {@code main.typ} is added to the project; {@code key=value} pairs become {@code sys.inputs}.
 */
public final class Main {

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: typst-pdf <main.typ> <out.pdf> [key=value...]");
      System.exit(2);
    }
    Path main = Path.of(args[0]).toAbsolutePath();
    Path root = main.getParent();

    long t0 = System.nanoTime();
    TypstCompiler compiler = TypstCompiler.create();
    long t1 = System.nanoTime();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        compiler.addFile(projectPath(root, file), Files.readAllBytes(file));
      }
    }
    for (int i = 2; i < args.length; i++) {
      String[] keyValue = args[i].split("=", 2);
      compiler.setInput(keyValue[0], keyValue.length > 1 ? keyValue[1] : "");
    }

    CompileResult result = compiler.compile(projectPath(root, main));
    long t2 = System.nanoTime();
    result.warnings().forEach(System.err::println);
    if (!result.isSuccess()) {
      result.errors().forEach(System.err::println);
      System.exit(1);
    }
    Files.write(Path.of(args[1]), result.pdf());

    // A second compilation reuses Typst's incremental cache.
    compiler.compile(projectPath(root, main));
    long t3 = System.nanoTime();
    System.err.printf(
        "init %d ms, compile %d ms, recompile %d ms%n",
        millis(t0, t1), millis(t1, t2), millis(t2, t3));
  }

  private static String projectPath(Path root, Path file) {
    return "/" + root.relativize(file).toString().replace('\\', '/');
  }

  private static long millis(long from, long to) {
    return (to - from) / 1_000_000;
  }
}
