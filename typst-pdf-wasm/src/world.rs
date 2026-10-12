//! An in-memory [`World`]: every file and font is pushed by the host.

use std::collections::HashMap;
use std::sync::OnceLock;

use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime, Dict, Duration, IntoValue};
use typst::syntax::{FileId, RootedPath, Source, VirtualPath, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World};

/// A file pushed by the host, decoded as a Typst source on first access.
struct Slot {
    bytes: Bytes,
    source: OnceLock<FileResult<Source>>,
}

/// The current time as provided by the host.
#[derive(Clone, Copy)]
pub struct Now {
    /// Seconds since the Unix epoch (UTC).
    pub unix_seconds: i64,
    /// Offset of the local timezone from UTC, in minutes.
    pub offset_minutes: i32,
}

pub struct MemoryWorld {
    library: LazyHash<Library>,
    inputs: Dict,
    book: LazyHash<FontBook>,
    fonts: Vec<Font>,
    /// Number of fonts that are not removed by [`MemoryWorld::reset`].
    builtin_fonts: usize,
    files: HashMap<FileId, Slot>,
    main: FileId,
    now: Option<Now>,
}

impl MemoryWorld {
    pub fn new() -> Self {
        let fonts = builtin_fonts();
        Self {
            library: LazyHash::new(Library::default()),
            inputs: Dict::new(),
            book: LazyHash::new(FontBook::from_fonts(&fonts)),
            builtin_fonts: fonts.len(),
            fonts,
            files: HashMap::new(),
            main: file_id("/main.typ").unwrap(),
            now: None,
        }
    }

    /// Removes files, inputs and host fonts. Built-in fonts are kept.
    pub fn reset(&mut self) {
        self.files.clear();
        self.now = None;
        if !self.inputs.is_empty() {
            self.inputs = Dict::new();
            self.rebuild_library();
        }
        if self.fonts.len() != self.builtin_fonts {
            self.fonts.truncate(self.builtin_fonts);
            self.book = LazyHash::new(FontBook::from_fonts(&self.fonts));
        }
    }

    pub fn add_file(&mut self, id: FileId, data: Vec<u8>) {
        let slot = Slot {
            bytes: Bytes::new(data),
            source: OnceLock::new(),
        };
        self.files.insert(id, slot);
    }

    /// Registers every face of a font file and returns how many were found.
    pub fn add_font(&mut self, data: Vec<u8>) -> usize {
        let before = self.fonts.len();
        self.fonts.extend(Font::iter(Bytes::new(data)));
        let added = self.fonts.len() - before;
        if added > 0 {
            self.book = LazyHash::new(FontBook::from_fonts(&self.fonts));
        }
        added
    }

    pub fn set_input(&mut self, key: &str, value: &str) {
        self.inputs.insert(key.into(), value.into_value());
        self.rebuild_library();
    }

    pub fn set_now(&mut self, now: Option<Now>) {
        self.now = now;
    }

    pub fn now(&self) -> Option<Now> {
        self.now
    }

    pub fn set_main(&mut self, id: FileId) {
        self.main = id;
    }

    fn rebuild_library(&mut self) {
        let library = Library::builder().with_inputs(self.inputs.clone()).build();
        self.library = LazyHash::new(library);
    }

    fn slot(&self, id: FileId) -> FileResult<&Slot> {
        if let VirtualRoot::Package(spec) = id.root() {
            return Err(FileError::Other(Some(
                format!("packages are not supported ({spec})").into(),
            )));
        }
        self.files
            .get(&id)
            .ok_or_else(|| FileError::NotFound(id.vpath().get_with_slash().into()))
    }
}

impl World for MemoryWorld {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        &self.book
    }

    fn main(&self) -> FileId {
        self.main
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        let slot = self.slot(id)?;
        slot.source
            .get_or_init(|| {
                let text = std::str::from_utf8(slot.bytes.as_slice())
                    .map_err(|_| FileError::InvalidUtf8)?;
                Ok(Source::new(id, text.into()))
            })
            .clone()
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.slot(id).map(|slot| slot.bytes.clone())
    }

    fn font(&self, index: usize) -> Option<Font> {
        self.fonts.get(index).cloned()
    }

    fn today(&self, offset: Option<Duration>) -> Option<Datetime> {
        let now = self.now?;
        let offset_seconds = match offset {
            Some(duration) => duration.seconds() as i64,
            None => i64::from(now.offset_minutes) * 60,
        };
        let days = (now.unix_seconds + offset_seconds).div_euclid(86_400);
        let (year, month, day) = civil_from_days(days);
        Datetime::from_ymd(year, month, day)
    }
}

/// Interns a project path such as `/main.typ` or `images/logo.png`.
pub fn file_id(path: &str) -> Option<FileId> {
    let vpath = VirtualPath::new(path).ok()?;
    Some(RootedPath::new(VirtualRoot::Project, vpath).intern())
}

/// Converts seconds since the Unix epoch to a UTC datetime.
pub fn datetime_from_unix(unix_seconds: i64) -> Option<Datetime> {
    let (year, month, day) = civil_from_days(unix_seconds.div_euclid(86_400));
    let secs = unix_seconds.rem_euclid(86_400);
    Datetime::from_ymd_hms(
        year,
        month,
        day,
        (secs / 3600) as u8,
        (secs / 60 % 60) as u8,
        (secs % 60) as u8,
    )
}

/// Howard Hinnant's `civil_from_days`: days since 1970-01-01 to (y, m, d).
fn civil_from_days(days: i64) -> (i32, u8, u8) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u8;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u8;
    let year = (yoe + era * 400 + i64::from(month <= 2)) as i32;
    (year, month, day)
}

#[cfg(feature = "fonts")]
fn builtin_fonts() -> Vec<Font> {
    typst_assets::fonts()
        .flat_map(|data| Font::iter(Bytes::new(data)))
        .collect()
}

#[cfg(not(feature = "fonts"))]
fn builtin_fonts() -> Vec<Font> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(-1), (1969, 12, 31));
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
    }
}
