#!/usr/bin/env bash
# Builds the Typst PDF WebAssembly module into dist/typst-pdf.wasm.
#
# Usage: scripts/build-wasm.sh [--no-fonts]
#   --no-fonts  do not embed Typst's default fonts (the host must add fonts)
#
# wasm-opt (binaryen) is used when found in PATH or $WASM_OPT.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
features=()
if [[ "${1:-}" == "--no-fonts" ]]; then
  features=(--no-default-features)
fi

cargo build --manifest-path "$root/Cargo.toml" --release \
  --target wasm32-unknown-unknown "${features[@]}"

input="$root/target/wasm32-unknown-unknown/release/typst_pdf_wasm.wasm"
output="$root/dist/typst-pdf.wasm"
mkdir -p "$root/dist"

wasm_opt="${WASM_OPT:-$(command -v wasm-opt || true)}"
if [[ -n "$wasm_opt" ]]; then
  # Only enable the features rustc emits by default for wasm32-unknown-unknown.
  # -ocimfs bounds the inlining of single-caller functions: unbounded, it
  # yields functions above the JVM 64 KiB method limit, which Chicory can then
  # only run in its interpreter.
  "$wasm_opt" -Oz -ocimfs 200 --strip-producers \
    --enable-bulk-memory --enable-nontrapping-float-to-int --enable-sign-ext \
    --enable-mutable-globals --enable-multivalue --enable-reference-types \
    "$input" -o "$output"
else
  echo "warning: wasm-opt not found, copying the unoptimized module" >&2
  cp "$input" "$output"
fi
ls -l "$output"
