//! A Java `.properties` file (the format Lithium, FerriteCore and Iris keep their settings in), read and
//! changed without losing anything: comments, blank lines, order, line endings and lines Flint doesn't
//! touch are written back exactly, and a change replaces only the line of the key it changes.
//!
//! Reading follows `java.util.Properties.load`: a line whose first non-blank character is `#` or `!` is a
//! comment; a line ending in an odd number of backslashes continues on the next one; the key runs to the
//! first unescaped `=`, `:` or blank, then blanks and one `=` or `:` are skipped; `\t`, `\n`, `\r`, `\f`
//! and `\uXXXX` are escapes and any other escaped character stands for itself. A key set twice keeps its
//! last value.

/// One logical line: an entry (possibly spread over several natural lines) or anything else, with the
/// exact text it had, including inner line breaks but not the final one.
#[derive(Clone, Debug, PartialEq)]
enum Line {
    Entry { key: String, value: String, raw: String },
    Other(String),
}

impl Line {
    fn raw(&self) -> &str {
        match self {
            Line::Entry { raw, .. } | Line::Other(raw) => raw,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct PropsDoc {
    lines: Vec<(Line, &'static str)>,
}

impl PropsDoc {
    /// Reads the file's bytes; text that isn't UTF-8 is refused (it couldn't be written back exactly).
    pub fn parse(bytes: &[u8]) -> Result<PropsDoc, String> {
        let text = std::str::from_utf8(bytes).map_err(|_| "isn't UTF-8 text".to_owned())?;
        let mut natural: Vec<(&str, &'static str)> = Vec::new();
        let mut rest = text;
        while !rest.is_empty() {
            let (line, ending, next) = match rest.find(['\n', '\r']) {
                Some(at) if rest[at..].starts_with("\r\n") => (&rest[..at], "\r\n", &rest[at + 2..]),
                Some(at) if rest.as_bytes()[at] == b'\r' => (&rest[..at], "\r", &rest[at + 1..]),
                Some(at) => (&rest[..at], "\n", &rest[at + 1..]),
                None => (rest, "", ""),
            };
            natural.push((line, ending));
            rest = next;
        }
        let mut lines = Vec::new();
        let mut i = 0;
        while i < natural.len() {
            let (first, mut ending) = natural[i];
            let trimmed = first.trim_start_matches([' ', '\t', '\x0c']);
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
                lines.push((Line::Other(first.to_owned()), ending));
                i += 1;
                continue;
            }
            // Join continuation lines, keeping their exact text for writing back.
            let mut raw = first.to_owned();
            let mut logical = trimmed.to_owned();
            while ends_with_odd_backslashes(&logical) && i + 1 < natural.len() {
                logical.pop();
                raw.push_str(ending);
                i += 1;
                let (next, next_ending) = natural[i];
                raw.push_str(next);
                logical.push_str(next.trim_start_matches([' ', '\t', '\x0c']));
                ending = next_ending;
            }
            let (key, value) = split_entry(&logical);
            lines.push((Line::Entry { key, value, raw }, ending));
            i += 1;
        }
        Ok(PropsDoc { lines })
    }

    /// The value Java reads for `key` (its last line), unescaped.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.lines.iter().rev().find_map(|(line, _)| match line {
            Line::Entry { key: k, value, .. } if k == key => Some(value.as_str()),
            _ => None,
        })
    }

    /// Every key with its value, as Java ends up with them (last line wins), sorted by key.
    pub fn entries(&self) -> std::collections::BTreeMap<String, String> {
        let mut out = std::collections::BTreeMap::new();
        for (line, _) in &self.lines {
            if let Line::Entry { key, value, .. } = line {
                out.insert(key.clone(), value.clone());
            }
        }
        out
    }

    /// Sets `key` to `value` on its last line (which becomes `key=value`, escaped), or adds it at the end.
    /// Every other line stays exactly as it is.
    pub fn set(&mut self, key: &str, value: &str) {
        let raw = format!("{}={}", escape(key, true), escape(value, false));
        if let Some((line, _)) = self.lines.iter_mut().rev().find(|(line, _)| matches!(line, Line::Entry { key: k, .. } if k == key)) {
            *line = Line::Entry { key: key.to_owned(), value: value.to_owned(), raw };
            return;
        }
        let ending = if self.lines.iter().any(|(_, e)| *e == "\r\n") { "\r\n" } else { "\n" };
        let last_ending = match self.lines.last_mut() {
            Some((_, last)) if last.is_empty() => std::mem::replace(last, ending),
            Some((_, last)) => *last,
            None => ending,
        };
        self.lines.push((Line::Entry { key: key.to_owned(), value: value.to_owned(), raw }, if last_ending.is_empty() { "" } else { ending }));
    }
}

impl std::fmt::Display for PropsDoc {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for (line, ending) in &self.lines {
            f.write_str(line.raw())?;
            f.write_str(ending)?;
        }
        Ok(())
    }
}

fn ends_with_odd_backslashes(text: &str) -> bool {
    text.bytes().rev().take_while(|b| *b == b'\\').count() % 2 == 1
}

/// Splits a logical line (leading blanks already removed) into its key and value, both unescaped.
fn split_entry(line: &str) -> (String, String) {
    let chars: Vec<char> = line.chars().collect();
    let mut key_end = chars.len();
    let mut value_start = chars.len();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '\\' => i += 2,
            '=' | ':' => {
                key_end = i;
                value_start = i + 1;
                break;
            }
            ' ' | '\t' | '\x0c' => {
                key_end = i;
                let mut j = i;
                while j < chars.len() && matches!(chars[j], ' ' | '\t' | '\x0c') {
                    j += 1;
                }
                if j < chars.len() && matches!(chars[j], '=' | ':') {
                    j += 1;
                }
                value_start = j;
                break;
            }
            _ => i += 1,
        }
    }
    let key_end = key_end.min(chars.len());
    let mut v = value_start.min(chars.len());
    while v < chars.len() && matches!(chars[v], ' ' | '\t' | '\x0c') {
        v += 1;
    }
    (unescape(&chars[..key_end]), unescape(&chars[v..]))
}

fn unescape(chars: &[char]) -> String {
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] != '\\' || i + 1 >= chars.len() {
            if chars[i] != '\\' {
                out.push(chars[i]);
            }
            i += 1;
            continue;
        }
        let next = chars[i + 1];
        i += 2;
        match next {
            't' => out.push('\t'),
            'n' => out.push('\n'),
            'r' => out.push('\r'),
            'f' => out.push('\x0c'),
            'u' if i + 4 <= chars.len() => {
                let hex: String = chars[i..i + 4].iter().collect();
                match u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    Some(c) => {
                        out.push(c);
                        i += 4;
                    }
                    None => out.push('u'),
                }
            }
            other => out.push(other),
        }
    }
    out
}

/// Escapes text the way `Properties.store` (`saveConvert`) does, so Java reads back exactly `text`: `=`, `:`,
/// `#` and `!` always, spaces in keys and a value's leading space, control and non-ASCII characters as `\uXXXX`.
fn escape(text: &str, key: bool) -> String {
    let mut out = String::new();
    for (i, c) in text.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\x0c' => out.push_str("\\f"),
            ' ' if key || i == 0 => out.push_str("\\ "),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            c if (c as u32) < 0x20 || (c as u32) > 0x7e => out.push_str(&format!("\\u{:04X}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const FERRITE: &str = "# Replace the blockstate neighbor table\nreplaceNeighborLookup = true\n# Use a slightly more compact representation\ncompactFastMap = false\n";

    #[test]
    fn an_untouched_file_is_written_back_byte_for_byte() {
        for text in [FERRITE, "a=1\r\nb : two\r\n", "! comment\n\nkey value with spaces\nlast=no newline", "multi=one \\\n    two\nx=y\n", ""] {
            assert_eq!(PropsDoc::parse(text.as_bytes()).unwrap().to_string(), text);
        }
    }

    #[test]
    fn keys_and_values_are_read_as_java_reads_them() {
        let doc = PropsDoc::parse(b"a=1\nb : two\nc   three\nd\\ e=f\\u0041\nmulti=one \\\n    two\nempty=\nbare\na=again\n").unwrap();
        assert_eq!(doc.get("a"), Some("again"), "the last line wins");
        assert_eq!(doc.get("b"), Some("two"));
        assert_eq!(doc.get("c"), Some("three"));
        assert_eq!(doc.get("d e"), Some("fA"));
        assert_eq!(doc.get("multi"), Some("one two"));
        assert_eq!(doc.get("empty"), Some(""));
        assert_eq!(doc.get("bare"), Some(""));
        let doc = PropsDoc::parse(FERRITE.as_bytes()).unwrap();
        assert_eq!(doc.get("replaceNeighborLookup"), Some("true"));
        assert_eq!(doc.entries().len(), 2);
    }

    #[test]
    fn a_change_touches_only_its_own_line() {
        let mut doc = PropsDoc::parse(FERRITE.as_bytes()).unwrap();
        doc.set("compactFastMap", "true");
        assert_eq!(doc.to_string(), FERRITE.replace("compactFastMap = false", "compactFastMap=true"));
        doc.set("mixin.new", "false");
        assert!(doc.to_string().ends_with("compactFastMap=true\nmixin.new=false\n"));
        let mut doc = PropsDoc::parse(b"# only a comment").unwrap();
        doc.set("shaderPack", "My Pack: v2.zip");
        assert_eq!(doc.to_string(), "# only a comment\nshaderPack=My Pack\\: v2.zip");
        let back = PropsDoc::parse(doc.to_string().as_bytes()).unwrap();
        assert_eq!(back.get("shaderPack"), Some("My Pack: v2.zip"));
    }

    #[test]
    fn values_that_need_escaping_read_back_the_same() {
        let mut doc = PropsDoc::parse(b"").unwrap();
        for value in [" leading", "tab\there", "back\\slash", "ünïcode", "=starts", "#hash"] {
            doc.set("k", value);
            assert_eq!(PropsDoc::parse(doc.to_string().as_bytes()).unwrap().get("k"), Some(value), "{value}");
        }
        assert!(PropsDoc::parse(&[0xff, 0xfe]).is_err());
    }
}
