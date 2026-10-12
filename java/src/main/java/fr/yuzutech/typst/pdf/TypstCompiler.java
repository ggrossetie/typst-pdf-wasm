package fr.yuzutech.typst.pdf;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import fr.yuzutech.typst.pdf.internal.TypstPdfModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Compiles Typst documents to PDF.
 *
 * <p>The Typst compiler runs as WebAssembly translated to JVM bytecode at build time by Chicory: no
 * native library and no Wasm interpreter (except for a few functions too large for the JVM, see the
 * README). Each compiler owns an isolated in-memory project: files, fonts and inputs are pushed by
 * the caller, nothing is read from the file system or the network.
 *
 * <pre>{@code
 * TypstCompiler compiler = TypstCompiler.create();
 * compiler.addFile("/main.typ", "= Hello\nWorld");
 * CompileResult result = compiler.compile();
 * byte[] pdf = result.pdf();
 * }</pre>
 *
 * <p>Instances are thread-safe but compile one document at a time; use several instances to
 * compile in parallel. Reusing an instance is cheaper than creating a new one and benefits from
 * Typst's incremental compilation.
 */
public final class TypstCompiler {

  /** Path of the main file used by {@link #compile()}. */
  public static final String DEFAULT_MAIN = "/main.typ";

  private static final int STATUS_OK = 0;
  private static final int STATUS_ERROR = 1;

  private final Memory memory;
  private final ExportFunction alloc;
  private final ExportFunction dealloc;
  private final ExportFunction reset;
  private final ExportFunction addFile;
  private final ExportFunction addFont;
  private final ExportFunction setInput;
  private final ExportFunction setNow;
  private final ExportFunction compile;
  private final ExportFunction outputPtr;
  private final ExportFunction outputLen;
  private final ExportFunction diagnosticsPtr;
  private final ExportFunction diagnosticsLen;

  private TypstCompiler(Instance instance) {
    this.memory = instance.memory();
    this.alloc = instance.export("alloc");
    this.dealloc = instance.export("dealloc");
    this.reset = instance.export("reset");
    this.addFile = instance.export("add_file");
    this.addFont = instance.export("add_font");
    this.setInput = instance.export("set_input");
    this.setNow = instance.export("set_now");
    this.compile = instance.export("compile");
    this.outputPtr = instance.export("output_ptr");
    this.outputLen = instance.export("output_len");
    this.diagnosticsPtr = instance.export("diagnostics_ptr");
    this.diagnosticsLen = instance.export("diagnostics_len");
  }

  /** Creates a compiler with an empty project. */
  public static TypstCompiler create() {
    Instance instance =
        Instance.builder(TypstPdfModule.load())
            .withMachineFactory(TypstPdfModule::create)
            .build();
    return new TypstCompiler(instance);
  }

  /**
   * Adds or replaces a file of the project, such as {@code /main.typ} or {@code /images/logo.png}.
   * Paths are resolved from the project root; packages ({@code @preview/...}) are not supported.
   */
  public synchronized TypstCompiler addFile(String path, byte[] content) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
    try (Buffer p = write(utf8(path));
        Buffer c = write(content)) {
      int status = call(addFile, p.ptr, p.len, c.ptr, c.len);
      if (status != STATUS_OK) {
        throw new IllegalArgumentException("Invalid path: " + path);
      }
    }
    return this;
  }

  /** Adds or replaces a text file of the project, encoded as UTF-8. */
  public TypstCompiler addFile(String path, String content) {
    return addFile(path, utf8(Objects.requireNonNull(content, "content")));
  }

  /**
   * Adds a font (TTF, OTF, TTC or OTC).
   *
   * @return the number of font faces found, 0 if the data is not a font
   */
  public synchronized int addFont(byte[] font) {
    Objects.requireNonNull(font, "font");
    try (Buffer f = write(font)) {
      return call(addFont, f.ptr, f.len);
    }
  }

  /** Sets a value available in the document as {@code sys.inputs.<key>}. */
  public synchronized TypstCompiler setInput(String key, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    try (Buffer k = write(utf8(key));
        Buffer v = write(utf8(value))) {
      call(setInput, k.ptr, k.len, v.ptr, v.len);
    }
    return this;
  }

  /**
   * Sets the current time, used by {@code datetime.today()} and as the PDF creation date. When not
   * set, {@code datetime.today()} fails and the PDF has no creation date, which makes the output
   * reproducible.
   */
  public synchronized TypstCompiler setNow(Instant now, ZoneOffset offset) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(offset, "offset");
    setNow.apply(now.getEpochSecond(), offset.getTotalSeconds() / 60);
    return this;
  }

  /** Removes all files, fonts added with {@link #addFont}, inputs and the current time. */
  public synchronized TypstCompiler reset() {
    reset.apply();
    return this;
  }

  /** Compiles {@value #DEFAULT_MAIN}. */
  public CompileResult compile() {
    return compile(DEFAULT_MAIN);
  }

  /** Compiles the given main file of the project. */
  public synchronized CompileResult compile(String main) {
    Objects.requireNonNull(main, "main");
    int status;
    try (Buffer m = write(utf8(main))) {
      status = call(compile, m.ptr, m.len);
    }
    if (status != STATUS_OK && status != STATUS_ERROR) {
      throw new IllegalArgumentException("Invalid main path: " + main);
    }
    byte[] pdf = status == STATUS_OK ? read(outputPtr, outputLen) : null;
    String json = new String(read(diagnosticsPtr, diagnosticsLen), StandardCharsets.UTF_8);
    return new CompileResult(pdf, DiagnosticParser.parse(json));
  }

  private byte[] read(ExportFunction ptr, ExportFunction len) {
    int length = (int) len.apply()[0];
    if (length == 0) {
      return new byte[0];
    }
    return memory.readBytes((int) ptr.apply()[0], length);
  }

  private Buffer write(byte[] bytes) {
    int ptr = bytes.length == 0 ? 0 : (int) alloc.apply(bytes.length)[0];
    if (bytes.length != 0) {
      memory.write(ptr, bytes);
    }
    return new Buffer(ptr, bytes.length);
  }

  private static int call(ExportFunction function, long... args) {
    return (int) function.apply(args)[0];
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** A buffer in the module's linear memory, freed on close. */
  private final class Buffer implements AutoCloseable {
    final int ptr;
    final int len;

    Buffer(int ptr, int len) {
      this.ptr = ptr;
      this.len = len;
    }

    @Override
    public void close() {
      if (len != 0) {
        dealloc.apply(ptr, len);
      }
    }
  }
}
