//! A profile's `options.txt`, read and written without losing anything.
//!
//! Minecraft reads the file line by line (Java's `BufferedReader.lines`, so `\n`, `\r\n` and `\r` all end a
//! line), splits each line at its first `:` and keeps the last value for a key that appears twice
//! (`Options.load` puts every line into one tag, so a later line replaces an earlier one). A line without a
//! `:` is skipped. This model keeps every line, its own line ending, the order, unknown and malformed lines
//! and a missing final newline, so a document that isn't changed is written back byte for byte, and a change
//! touches only the line it changes.

use serde_json::Value as Json;

/// One line of the file, without its line ending.
#[derive(Clone, Debug, PartialEq)]
pub enum Line {
    /// `key:value`, split at the first `:` as Minecraft does. `raw` is the line exactly as read (or as Flint
    /// wrote it), so an untouched entry is written back unchanged.
    Entry { key: String, value: String, raw: String },
    /// A blank or malformed line, kept as it is.
    Other(String),
}

impl Line {
    fn parse(text: &str) -> Line {
        match text.split_once(':') {
            Some((key, value)) => Line::Entry { key: key.to_owned(), value: value.to_owned(), raw: text.to_owned() },
            None => Line::Other(text.to_owned()),
        }
    }

    fn raw(&self) -> &str {
        match self {
            Line::Entry { raw, .. } => raw,
            Line::Other(raw) => raw,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct OptionsDoc {
    /// Each line with the ending that followed it ("" for a last line without a newline).
    lines: Vec<(Line, &'static str)>,
}

impl OptionsDoc {
    /// Reads a file's bytes. Minecraft reads options.txt as UTF-8 and replaces bytes that aren't (Guava's
    /// `Files.newReader`, `Options.load`), so it still loads such a file; but Flint couldn't write it back
    /// exactly as it is, so text that isn't UTF-8 is refused rather than guessed at (the profile is read-only).
    pub fn parse(bytes: &[u8]) -> Result<OptionsDoc, String> {
        let text = std::str::from_utf8(bytes).map_err(|_| "options.txt has text that isn't UTF-8, so Flint won't change it.".to_owned())?;
        let mut lines = Vec::new();
        let mut rest = text;
        while !rest.is_empty() {
            let (line, ending, next) = match rest.find(['\n', '\r']) {
                Some(at) if rest[at..].starts_with("\r\n") => (&rest[..at], "\r\n", &rest[at + 2..]),
                Some(at) if rest.as_bytes()[at] == b'\r' => (&rest[..at], "\r", &rest[at + 1..]),
                Some(at) => (&rest[..at], "\n", &rest[at + 1..]),
                None => (rest, "", ""),
            };
            lines.push((Line::parse(line), ending));
            rest = next;
        }
        Ok(OptionsDoc { lines })
    }

    /// A new file for a profile that has none yet: Minecraft runs every data fixer on a file without a
    /// `version:` line (and drops the whole file when its key binds are names), so it always gets one.
    pub fn new_file(data_version: u32) -> OptionsDoc {
        let mut doc = OptionsDoc { lines: Vec::new() };
        doc.set("version", &data_version.to_string());
        doc
    }

    /// The value Minecraft uses for `key`: the last line that has it.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.lines.iter().rev().find_map(|(line, _)| match line {
            Line::Entry { key: k, value, .. } if k == key => Some(value.as_str()),
            _ => None,
        })
    }

    pub fn contains(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    /// Changes the value Minecraft uses for `key` (its last line), or adds the key at the end of the file.
    /// Every other line stays exactly as it is.
    pub fn set(&mut self, key: &str, value: &str) {
        let raw = format!("{key}:{value}");
        if let Some((line, _)) = self.lines.iter_mut().rev().find(|(line, _)| matches!(line, Line::Entry { key: k, .. } if k == key)) {
            *line = Line::Entry { key: key.to_owned(), value: value.to_owned(), raw };
            return;
        }
        let ending = self.ending();
        // The new line becomes the last one, so it inherits the old last line's ending: a file that ended
        // without a newline still does.
        let last_ending = match self.lines.last_mut() {
            Some((_, last)) => std::mem::replace(last, ending),
            None => ending,
        };
        self.lines.push((Line::Entry { key: key.to_owned(), value: value.to_owned(), raw }, last_ending));
    }

    /// The `version:` line's number as Minecraft reads it (`Integer.parseInt`); None when the line is missing
    /// or isn't a number, which Minecraft treats as version 0 and upgrades from scratch.
    pub fn data_version(&self) -> Option<i64> {
        self.get("version").and_then(java_int)
    }

    /// Keys of every entry, in file order (a duplicated key appears more than once).
    pub fn keys(&self) -> impl Iterator<Item = &str> {
        self.lines.iter().filter_map(|(line, _)| match line {
            Line::Entry { key, .. } => Some(key.as_str()),
            Line::Other(_) => None,
        })
    }

    pub fn line_count(&self) -> usize {
        self.lines.len()
    }

    /// The line ending most of the file uses, for lines Flint adds.
    fn ending(&self) -> &'static str {
        let crlf = self.lines.iter().filter(|(_, e)| *e == "\r\n").count();
        let lf = self.lines.iter().filter(|(_, e)| *e == "\n").count();
        let cr = self.lines.iter().filter(|(_, e)| *e == "\r").count();
        if crlf > lf && crlf >= cr {
            "\r\n"
        } else if cr > lf && cr > crlf {
            "\r"
        } else {
            "\n"
        }
    }
}

impl std::fmt::Display for OptionsDoc {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for (line, ending) in &self.lines {
            f.write_str(line.raw())?;
            f.write_str(ending)?;
        }
        Ok(())
    }
}

/// `Integer.parseInt`: an optional sign and decimal digits, within `int`.
pub fn java_int(text: &str) -> Option<i64> {
    let digits = text.strip_prefix(['-', '+']).unwrap_or(text);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse::<i32>().ok().map(i64::from)
}

// ---------- values ----------

/// A setting's value once read from its text, compared by meaning rather than spelling (`1.0` and `1.00`
/// are the same double).
#[derive(Clone, Debug, PartialEq)]
pub enum Val {
    Bool(bool),
    Int(i64),
    Num(f64),
    Text(String),
}

/// How a value is spelled in options.txt, matching what Minecraft 26.3 writes and accepts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Codec {
    /// A JSON boolean (`OptionInstance<Boolean>`): `true` / `false`.
    Bool,
    /// A plain boolean field (`modelPart_*`): true only when the text is exactly `true`.
    PlainBool,
    /// A JSON integer.
    Int,
    /// A JSON number, written as Java's `Double.toString` writes it (`0.5`, `1.0`).
    Double,
    /// A quoted JSON string (`"fancy"`).
    JsonString,
    /// A JSON string, or a JSON boolean that Minecraft maps to one (`renderClouds`).
    JsonStringOrBool,
    /// The text as it is (`lang:en_us`).
    Raw,
    /// A key name (`key.keyboard.w`, `key.mouse.left`), stored as it is.
    KeyName,
}

impl Codec {
    /// Reads `text` the way Minecraft would, or None when Flint can't be sure what Minecraft makes of it
    /// (anything but the plain forms Minecraft itself writes).
    pub fn decode(self, text: &str) -> Option<Val> {
        match self {
            Codec::Bool | Codec::PlainBool => match text {
                "true" => Some(Val::Bool(true)),
                "false" => Some(Val::Bool(false)),
                _ => None,
            },
            Codec::Int => {
                if !is_json_number(text) || text.contains(['.', 'e', 'E']) {
                    return None;
                }
                text.parse::<i32>().ok().map(|n| Val::Int(n.into()))
            }
            Codec::Double => {
                if !is_json_number(text) {
                    return None;
                }
                text.parse::<f64>().ok().filter(|n| n.is_finite()).map(Val::Num)
            }
            Codec::JsonString => json_string(text).map(Val::Text),
            Codec::JsonStringOrBool => match text {
                "true" | "false" => Some(Val::Text(text.to_owned())),
                _ => json_string(text).map(Val::Text),
            },
            Codec::Raw | Codec::KeyName => Some(Val::Text(text.to_owned())),
        }
    }

    /// The text Minecraft would write for `value`.
    pub fn encode(self, value: &Val) -> String {
        match (self, value) {
            (_, Val::Bool(b)) => b.to_string(),
            (Codec::Double, Val::Num(n)) => java_double(*n),
            (Codec::Double, Val::Int(n)) => java_double(*n as f64),
            (_, Val::Int(n)) => n.to_string(),
            (_, Val::Num(n)) => java_double(*n),
            (Codec::JsonString | Codec::JsonStringOrBool, Val::Text(s)) => Json::String(s.clone()).to_string(),
            (_, Val::Text(s)) => s.clone(),
        }
    }
}

/// JSON's number grammar (Minecraft's lenient parser accepts more, but never writes it).
fn is_json_number(text: &str) -> bool {
    let rest = text.strip_prefix('-').unwrap_or(text);
    let int_len = rest.bytes().take_while(u8::is_ascii_digit).count();
    if int_len == 0 || (int_len > 1 && rest.starts_with('0')) {
        return false;
    }
    let mut rest = &rest[int_len..];
    if let Some(frac) = rest.strip_prefix('.') {
        let n = frac.bytes().take_while(u8::is_ascii_digit).count();
        if n == 0 {
            return false;
        }
        rest = &frac[n..];
    }
    if let Some(exp) = rest.strip_prefix(['e', 'E']) {
        let exp = exp.strip_prefix(['+', '-']).unwrap_or(exp);
        let n = exp.bytes().take_while(u8::is_ascii_digit).count();
        if n == 0 {
            return false;
        }
        rest = &exp[n..];
    }
    rest.is_empty()
}

fn json_string(text: &str) -> Option<String> {
    if !text.starts_with('"') {
        return None;
    }
    serde_json::from_str::<String>(text).ok()
}

/// Java's `Double.toString`, which Gson uses for options.txt: the shortest digits that read back as the same
/// number, always with a decimal point, and in `1.0E-4` form below 10^-3 or from 10^7.
pub fn java_double(n: f64) -> String {
    if n == 0.0 {
        return if n.is_sign_negative() { "-0.0" } else { "0.0" }.to_owned();
    }
    let abs = n.abs();
    if (1e-3..1e7).contains(&abs) {
        let text = format!("{n}");
        return if text.contains('.') { text } else { format!("{text}.0") };
    }
    let text = format!("{n:e}");
    let (mantissa, exponent) = text.split_once('e').unwrap_or((&text, "0"));
    let mantissa = if mantissa.contains('.') { mantissa.to_owned() } else { format!("{mantissa}.0") };
    format!("{mantissa}E{exponent}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use crate::gamesettings::testkit::FIXTURE_26_3;

    #[test]
    fn an_unchanged_file_is_written_back_byte_for_byte() {
        let dir = TestDir::new();
        let path = dir.0.join("options.txt");
        std::fs::write(&path, FIXTURE_26_3).unwrap();
        let bytes = std::fs::read(&path).unwrap();
        let doc = OptionsDoc::parse(&bytes).unwrap();
        assert_eq!(doc.to_string().as_bytes(), bytes.as_slice());
        assert_eq!(doc.data_version(), Some(5023));
        assert_eq!(doc.get("graphicsPreset"), Some("\"fancy\""));
        assert_eq!(doc.get("key_key.attack"), Some("key.mouse.left"));
        assert_eq!(doc.get("lastServer"), Some(""), "an empty value is still a value");
        assert_eq!(doc.line_count(), 194);
    }

    #[test]
    fn changing_one_value_touches_only_its_line() {
        let mut doc = OptionsDoc::parse(FIXTURE_26_3).unwrap();
        doc.set("renderDistance", "8");
        let before = std::str::from_utf8(FIXTURE_26_3).unwrap();
        let after = doc.to_string();
        let changed: Vec<_> = before.lines().zip(after.lines()).filter(|(a, b)| a != b).collect();
        assert_eq!(changed, vec![("renderDistance:16", "renderDistance:8")]);
        assert_eq!(before.len() - "16".len() + "8".len(), after.len());
    }

    #[test]
    fn crlf_and_lone_cr_endings_are_kept_per_line() {
        let text = "version:5023\r\nfov:0.0\rgamma:0.5\nmaxFps:120\r\n";
        let mut doc = OptionsDoc::parse(text.as_bytes()).unwrap();
        assert_eq!(doc.to_string(), text);
        assert_eq!(doc.get("gamma"), Some("0.5"));
        doc.set("fov", "0.5");
        assert_eq!(doc.to_string(), "version:5023\r\nfov:0.5\rgamma:0.5\nmaxFps:120\r\n");
        doc.set("narrator", "0");
        assert_eq!(doc.to_string(), "version:5023\r\nfov:0.5\rgamma:0.5\nmaxFps:120\r\nnarrator:0\r\n", "added lines follow the file's endings");
    }

    #[test]
    fn a_missing_final_newline_stays_missing_after_adding_a_key() {
        let mut doc = OptionsDoc::parse(b"version:5023\nfov:0.0").unwrap();
        assert_eq!(doc.to_string(), "version:5023\nfov:0.0");
        doc.set("gamma", "1.0");
        assert_eq!(doc.to_string(), "version:5023\nfov:0.0\ngamma:1.0");
        doc.set("fov", "0.25");
        assert_eq!(doc.to_string(), "version:5023\nfov:0.25\ngamma:1.0");
    }

    #[test]
    fn the_last_of_duplicate_keys_wins_and_is_the_one_edited() {
        // Options.load puts every line into one CompoundTag, so a later line replaces an earlier one.
        let mut doc = OptionsDoc::parse(b"maxFps:60\nfov:0.0\nmaxFps:90\n").unwrap();
        assert_eq!(doc.get("maxFps"), Some("90"));
        doc.set("maxFps", "120");
        assert_eq!(doc.to_string(), "maxFps:60\nfov:0.0\nmaxFps:120\n");
    }

    #[test]
    fn lines_without_a_colon_blank_lines_and_unknown_keys_are_kept() {
        let text = "version:5023\n\nthis line has no colon\n# a comment\nsomeModKey:{\"a\":1}\nlastServer:example.org:25565\n:empty key\nfov:\n";
        let mut doc = OptionsDoc::parse(text.as_bytes()).unwrap();
        assert_eq!(doc.to_string(), text);
        assert_eq!(doc.get("lastServer"), Some("example.org:25565"), "split at the first colon only");
        assert_eq!(doc.get("fov"), Some(""));
        assert_eq!(doc.get(""), Some("empty key"));
        assert_eq!(doc.get("this line has no colon"), None);
        doc.set("gamma", "0.5");
        assert!(doc.to_string().starts_with(text));
    }

    #[test]
    fn text_that_isnt_utf8_is_refused() {
        // Minecraft still loads such a file (it replaces the bad bytes), so the reason doesn't claim otherwise.
        assert_eq!(OptionsDoc::parse(b"version:5023\nlang:\xff\xfe\n").unwrap_err(), "options.txt has text that isn't UTF-8, so Flint won't change it.");
    }

    #[test]
    fn a_new_file_starts_with_the_data_version() {
        let mut doc = OptionsDoc::new_file(4903);
        doc.set("fov", "0.5");
        assert_eq!(doc.to_string(), "version:4903\nfov:0.5\n");
        assert_eq!(OptionsDoc::parse(b"").unwrap().to_string(), "");
    }

    #[test]
    fn the_version_line_is_read_like_integer_parse_int() {
        assert_eq!(OptionsDoc::parse(b"version:4786\n").unwrap().data_version(), Some(4786));
        assert_eq!(OptionsDoc::parse(b"version: 4786\n").unwrap().data_version(), None);
        assert_eq!(OptionsDoc::parse(b"version:abc\n").unwrap().data_version(), None);
        assert_eq!(OptionsDoc::parse(b"fov:0.0\n").unwrap().data_version(), None);
        assert_eq!(OptionsDoc::parse("\u{feff}version:5023\n".as_bytes()).unwrap().data_version(), None, "Minecraft doesn't strip a BOM either");
    }

    #[test]
    fn codecs_read_only_what_minecraft_writes() {
        assert_eq!(Codec::Bool.decode("true"), Some(Val::Bool(true)));
        assert_eq!(Codec::Bool.decode("TRUE"), None);
        assert_eq!(Codec::PlainBool.decode("false"), Some(Val::Bool(false)));
        assert_eq!(Codec::Int.decode("12"), Some(Val::Int(12)));
        assert_eq!(Codec::Int.decode("-3"), Some(Val::Int(-3)));
        assert_eq!(Codec::Int.decode("12.0"), None);
        assert_eq!(Codec::Int.decode("012"), None);
        assert_eq!(Codec::Int.decode("99999999999"), None);
        assert_eq!(Codec::Double.decode("0.5"), Some(Val::Num(0.5)));
        assert_eq!(Codec::Double.decode("1"), Some(Val::Num(1.0)));
        assert_eq!(Codec::Double.decode("1.0E-4"), Some(Val::Num(0.0001)));
        assert_eq!(Codec::Double.decode("NaN"), None);
        assert_eq!(Codec::Double.decode(".5"), None);
        assert_eq!(Codec::Double.decode(""), None);
        assert_eq!(Codec::JsonString.decode("\"fancy\""), Some(Val::Text("fancy".into())));
        assert_eq!(Codec::JsonString.decode("fancy"), None);
        assert_eq!(Codec::JsonStringOrBool.decode("true"), Some(Val::Text("true".into())));
        assert_eq!(Codec::JsonStringOrBool.decode("\"fast\""), Some(Val::Text("fast".into())));
        assert_eq!(Codec::Raw.decode("en_us"), Some(Val::Text("en_us".into())));
        assert_eq!(Codec::KeyName.decode("key.keyboard.w"), Some(Val::Text("key.keyboard.w".into())));
    }

    #[test]
    fn codecs_write_what_minecraft_writes() {
        assert_eq!(Codec::Bool.encode(&Val::Bool(false)), "false");
        assert_eq!(Codec::Int.encode(&Val::Int(260)), "260");
        assert_eq!(Codec::Double.encode(&Val::Num(1.0)), "1.0");
        assert_eq!(Codec::Double.encode(&Val::Num(0.4375)), "0.4375");
        assert_eq!(Codec::Double.encode(&Val::Int(2)), "2.0");
        assert_eq!(Codec::JsonString.encode(&Val::Text("custom".into())), "\"custom\"");
        assert_eq!(Codec::JsonStringOrBool.encode(&Val::Text("true".into())), "\"true\"");
        assert_eq!(Codec::Raw.encode(&Val::Text("en_us".into())), "en_us");
    }

    #[test]
    fn doubles_are_spelled_like_java() {
        assert_eq!(java_double(0.0), "0.0");
        assert_eq!(java_double(0.5), "0.5");
        assert_eq!(java_double(-0.25), "-0.25");
        assert_eq!(java_double(10.0), "10.0");
        assert_eq!(java_double(0.001), "0.001");
        assert_eq!(java_double(0.0001), "1.0E-4");
        assert_eq!(java_double(12345678.0), "1.2345678E7");
        assert_eq!(java_double(0.1 + 0.2), "0.30000000000000004");
    }
}
