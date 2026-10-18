# typst-pdf-wasm

A minimal [Typst](https://typst.app) → PDF compiler packaged as a WebAssembly module, with bindings for:

- **Java** (Maven): the Wasm module is translated to JVM bytecode at build time by
  [Chicory](https://chicory.dev), so the jar has no native library and no `.wasm` to interpret at
  runtime. It also works well with GraalVM native-image.
- **npm**: planned.

## Layout

| Path              | Content                                                        |
|-------------------|----------------------------------------------------------------|
| `typst-pdf-wasm/` | Rust `cdylib`: in-memory `World`, C-like ABI, JSON diagnostics |
| `scripts/`        | `build-wasm.sh` builds `dist/typst-pdf.wasm`                   |
| `java/`           | Maven module `fr.yuzutech.typst:typst-pdf`                     |

The crate only depends on `typst`, `typst-layout`, `typst-pdf` and, with the default `fonts`
feature, `typst-assets` (Typst's default fonts). No SVG/PNG/HTML export, no package downloads.

## Build

Requirements: Rust with the `wasm32-unknown-unknown` target, [binaryen](https://github.com/WebAssembly/binaryen)
(`wasm-opt`, optional but recommended), Java 17+ and Maven.

```sh
rustup target add wasm32-unknown-unknown
scripts/build-wasm.sh            # or --no-fonts to leave fonts to the host
cd java && mvn verify            # runs build-wasm.sh, then Chicory, then the tests
mvn verify -DskipWasmBuild=true  # reuse an existing dist/typst-pdf.wasm
```

## Java usage

```java
TypstCompiler compiler = TypstCompiler.create();
compiler.addFile("/main.typ", "#import \"template.typ\": conf\n#show: conf\n= Hello")
        .addFile("/template.typ", templateSource)
        .addFile("/logo.png", logoBytes)
        .setInput("version", "1.2.3");            // sys.inputs.version

CompileResult result = compiler.compile();       // or compile("/docs/report.typ")
if (result.isSuccess()) {
  Files.write(Path.of("out.pdf"), result.pdf());
} else {
  result.errors().forEach(System.err::println);  // error: /main.typ:3:5: …
}
```

- An instance is thread-safe but compiles one document at a time. Reuse it: it keeps Typst's
  incremental cache. `reset()` clears files, host fonts, inputs and time.
- `addFont(bytes)` adds TTF/OTF/TTC fonts on top of the embedded ones.
- `setNow(instant, offset)` enables `datetime.today()` and sets the PDF creation date. Without it,
  the output is reproducible.

## Wasm ABI

All strings are UTF-8; buffers are `(ptr, len)` pairs in the module's memory. The host allocates
with `alloc`, writes, calls, then frees with `dealloc` (the module copies what it keeps).

| Export                                      | Description                                        |
|---------------------------------------------|----------------------------------------------------|
| `alloc(len) -> ptr`, `dealloc(ptr, len)`    | Linear memory management                           |
| `add_file(path, path_len, data, data_len)`  | Adds/replaces a project file. Returns 0, or 2 for an invalid path |
| `add_font(data, data_len) -> faces`         | Adds a font, returns the number of faces           |
| `set_input(key, key_len, value, value_len)` | Sets `sys.inputs.<key>`                             |
| `set_now(unix_seconds: i64, offset_minutes)`| Current time for `datetime.today()` and PDF date   |
| `reset()`                                   | Clears files, host fonts, inputs and time          |
| `compile(main, main_len) -> status`         | 0 = PDF produced, 1 = errors, 2 = invalid path     |
| `output_ptr()`, `output_len()`              | PDF of the last compilation                        |
| `diagnostics_ptr()`, `diagnostics_len()`    | JSON diagnostics of the last compilation           |

Diagnostics:

```json
[
  {
    "severity": "error",
    "message": "unknown variable: x",
    "path": "/main.typ",
    "line": 2,
    "column": 2,
    "start": 7,
    "end": 8,
    "hints": []
  }
]
```

The module has no imports, so any Wasm runtime can host it.

## Limitations

- Packages (`@preview/…`) are not supported: everything must be added with `add_file`.
- Three functions are larger than the JVM's 64 KiB method limit and run in Chicory's interpreter
  (`interpreterFallback=WARN` lists them at build time): `typst_library::visualize::color::map`
  (built once per library), `regex_automata::meta::strategy::new` (regex compilation) and wasmi's
  executor (only used by Typst `plugin`s). `build-wasm.sh` passes `-ocimfs 200` to `wasm-opt` so
  that it does not inline more code into oversized functions (without it, 11 functions exceed the
  limit).
- The module is ~31 MB (~24 MB without fonts): besides fonts, Typst embeds hyphenation patterns,
  syntax definitions and bibliography styles.
