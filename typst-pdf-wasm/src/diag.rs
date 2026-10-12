//! Serializes diagnostics as JSON, without pulling in serde.
//!
//! ```json
//! [{"severity":"error","message":"…","path":"/main.typ","line":3,"column":5,
//!   "start":42,"end":47,"hints":["…"]}]
//! ```
//!
//! `line` and `column` are 1-based (column counted in characters); `start` and
//! `end` are byte offsets. Location fields are omitted when the diagnostic does
//! not point into a file.

use std::fmt::Write;

use typst::diag::{Severity, SourceDiagnostic};
use typst::{World, WorldExt};

pub fn to_json<'a>(
    world: &dyn World,
    diagnostics: impl IntoIterator<Item = &'a SourceDiagnostic>,
) -> String {
    let mut out = String::from("[");
    for (i, diag) in diagnostics.into_iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let severity = match diag.severity {
            Severity::Error => "error",
            Severity::Warning => "warning",
        };
        out.push_str("{\"severity\":\"");
        out.push_str(severity);
        out.push_str("\",\"message\":");
        push_str(&mut out, &diag.message);

        if let Some(id) = diag.span.id() {
            out.push_str(",\"path\":");
            push_str(&mut out, id.vpath().get_with_slash());
            if let Some(range) = world.range(diag.span) {
                if let Some((line, column)) = world
                    .source(id)
                    .ok()
                    .and_then(|source| source.lines().byte_to_line_column(range.start))
                {
                    let _ = write!(out, ",\"line\":{},\"column\":{}", line + 1, column + 1);
                }
                let _ = write!(out, ",\"start\":{},\"end\":{}", range.start, range.end);
            }
        }

        out.push_str(",\"hints\":[");
        for (j, hint) in diag.hints.iter().enumerate() {
            if j > 0 {
                out.push(',');
            }
            push_str(&mut out, &hint.v);
        }
        out.push_str("]}");
    }
    out.push(']');
    out
}

fn push_str(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}
