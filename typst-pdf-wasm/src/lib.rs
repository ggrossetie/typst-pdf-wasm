//! Typst-to-PDF compiler exposed through a small C-like WebAssembly ABI.
//!
//! The host owns a single compiler instance. All strings are UTF-8 and all
//! buffers are passed as `(ptr, len)` pairs into the module's linear memory:
//!
//! 1. `alloc(len)` a buffer, write the bytes, call the function, then
//!    `dealloc(ptr, len)`; the module copies what it keeps.
//! 2. Push files with `add_file`, optionally fonts (`add_font`), inputs
//!    (`set_input`) and the current time (`set_now`).
//! 3. `compile(main)` returns a status; the PDF is then available through
//!    `output_ptr`/`output_len` and diagnostics (JSON) through
//!    `diagnostics_ptr`/`diagnostics_len`. Both stay valid until the next call
//!    to `compile` or `reset`.

mod diag;
mod world;

use std::alloc::{Layout, alloc as std_alloc, dealloc as std_dealloc};
use std::sync::{LazyLock, Mutex, MutexGuard};

use typst::foundations::Smart;
use typst_layout::PagedDocument;
use typst_pdf::{PdfOptions, Timestamp};

use crate::world::{MemoryWorld, Now, datetime_from_unix, file_id};

/// `compile` succeeded: the output holds a PDF.
pub const STATUS_OK: i32 = 0;
/// `compile` failed: the diagnostics hold at least one error.
pub const STATUS_ERROR: i32 = 1;
/// An argument was invalid (bad UTF-8 or path).
pub const STATUS_INVALID_ARGUMENT: i32 = 2;

struct State {
    world: MemoryWorld,
    output: Vec<u8>,
    diagnostics: Vec<u8>,
}

static STATE: LazyLock<Mutex<State>> = LazyLock::new(|| {
    Mutex::new(State {
        world: MemoryWorld::new(),
        output: Vec::new(),
        diagnostics: b"[]".to_vec(),
    })
});

fn state() -> MutexGuard<'static, State> {
    STATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// # Safety
/// `(ptr, len)` must describe readable memory (or `len` must be 0).
unsafe fn bytes<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }
    }
}

/// # Safety
/// See [`bytes`].
unsafe fn str<'a>(ptr: *const u8, len: usize) -> Option<&'a str> {
    std::str::from_utf8(unsafe { bytes(ptr, len) }).ok()
}

/// Allocates `len` bytes in linear memory. Returns 0 if `len` is 0.
#[unsafe(no_mangle)]
pub extern "C" fn alloc(len: usize) -> *mut u8 {
    if len == 0 {
        return std::ptr::null_mut();
    }
    unsafe { std_alloc(Layout::from_size_align_unchecked(len, 1)) }
}

/// Frees a buffer returned by [`alloc`].
///
/// # Safety
/// `ptr` must come from `alloc(len)` with the same `len`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: usize) {
    if !ptr.is_null() && len != 0 {
        unsafe { std_dealloc(ptr, Layout::from_size_align_unchecked(len, 1)) }
    }
}

/// Forgets all files, inputs, host fonts and the current time.
#[unsafe(no_mangle)]
pub extern "C" fn reset() {
    let mut state = state();
    state.world.reset();
    state.output = Vec::new();
    state.diagnostics = b"[]".to_vec();
}

/// Adds (or replaces) a file in the virtual project, e.g. `/main.typ` or
/// `/images/logo.png`.
///
/// # Safety
/// Pointers must describe readable memory.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn add_file(
    path_ptr: *const u8,
    path_len: usize,
    data_ptr: *const u8,
    data_len: usize,
) -> i32 {
    let Some(id) = unsafe { str(path_ptr, path_len) }.and_then(file_id) else {
        return STATUS_INVALID_ARGUMENT;
    };
    let data = unsafe { bytes(data_ptr, data_len) }.to_vec();
    state().world.add_file(id, data);
    STATUS_OK
}

/// Adds a font file (TTF, OTF or collection). Returns the number of faces
/// registered, 0 if the data is not a font.
///
/// # Safety
/// Pointers must describe readable memory.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn add_font(data_ptr: *const u8, data_len: usize) -> i32 {
    let data = unsafe { bytes(data_ptr, data_len) }.to_vec();
    state().world.add_font(data) as i32
}

/// Sets a string available as `sys.inputs.<key>`.
///
/// # Safety
/// Pointers must describe readable memory.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn set_input(
    key_ptr: *const u8,
    key_len: usize,
    value_ptr: *const u8,
    value_len: usize,
) -> i32 {
    let (Some(key), Some(value)) = (unsafe { str(key_ptr, key_len) }, unsafe {
        str(value_ptr, value_len)
    }) else {
        return STATUS_INVALID_ARGUMENT;
    };
    state().world.set_input(key, value);
    STATUS_OK
}

/// Sets the current time, used by `datetime.today()` and as the PDF creation
/// date. Without it, `datetime.today()` fails and the PDF has no date.
#[unsafe(no_mangle)]
pub extern "C" fn set_now(unix_seconds: i64, offset_minutes: i32) {
    state().world.set_now(Some(Now {
        unix_seconds,
        offset_minutes,
    }));
}

/// Compiles the given main file to PDF. Returns [`STATUS_OK`],
/// [`STATUS_ERROR`] or [`STATUS_INVALID_ARGUMENT`].
///
/// # Safety
/// Pointers must describe readable memory.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn compile(main_ptr: *const u8, main_len: usize) -> i32 {
    let mut state = state();
    state.output = Vec::new();
    state.diagnostics = b"[]".to_vec();

    let Some(main) = unsafe { str(main_ptr, main_len) }.and_then(file_id) else {
        return STATUS_INVALID_ARGUMENT;
    };
    state.world.set_main(main);

    let world = &state.world;
    let warned = typst::compile::<PagedDocument>(world);
    let mut diagnostics = warned.warnings.to_vec();
    let result = warned.output.and_then(|document| {
        let options = PdfOptions {
            ident: Smart::Auto,
            timestamp: world.now().and_then(timestamp),
            ..PdfOptions::default()
        };
        typst_pdf::pdf(&document, &options)
    });

    let (status, output) = match result {
        Ok(pdf) => (STATUS_OK, pdf),
        Err(errors) => {
            diagnostics.extend(errors);
            (STATUS_ERROR, Vec::new())
        }
    };
    let json = diag::to_json(world, &diagnostics).into_bytes();
    state.output = output;
    state.diagnostics = json;

    // Bound the memory held by the incremental compilation cache.
    typst::comemo::evict(10);
    status
}

fn timestamp(now: Now) -> Option<Timestamp> {
    let local = now.unix_seconds + i64::from(now.offset_minutes) * 60;
    Timestamp::new_local(datetime_from_unix(local)?, now.offset_minutes)
}

/// Pointer to the PDF produced by the last successful `compile`.
#[unsafe(no_mangle)]
pub extern "C" fn output_ptr() -> *const u8 {
    state().output.as_ptr()
}

/// Length of the PDF produced by the last successful `compile`.
#[unsafe(no_mangle)]
pub extern "C" fn output_len() -> usize {
    state().output.len()
}

/// Pointer to the JSON diagnostics of the last `compile`.
#[unsafe(no_mangle)]
pub extern "C" fn diagnostics_ptr() -> *const u8 {
    state().diagnostics.as_ptr()
}

/// Length of the JSON diagnostics of the last `compile`.
#[unsafe(no_mangle)]
pub extern "C" fn diagnostics_len() -> usize {
    state().diagnostics.len()
}
