//! Changing values inside a mod's JSON settings file without rewriting the rest of it: the file keeps its
//! own key order, spacing and any keys Flint doesn't change, exactly as the mod wrote them. Only values of
//! keys the file already has are replaced (Auto Sync never adds or removes keys in a mod's file).

use serde_json::Value as Json;

/// A value in the file that isn't an object (arrays count as one value), by its path of object keys.
#[derive(Clone, Debug, PartialEq)]
pub struct Leaf {
    pub path: Vec<String>,
    pub start: usize,
    pub end: usize,
}

/// Every non-object value in `text` with its byte span, in file order. Err when `text` isn't a JSON object.
pub fn leaves(text: &str) -> Result<Vec<Leaf>, String> {
    let mut scanner = Scanner { text: text.as_bytes(), at: 0, out: Vec::new() };
    scanner.ws();
    if scanner.peek() != Some(b'{') {
        return Err("isn't a JSON object".into());
    }
    scanner.value(&mut Vec::new())?;
    scanner.ws();
    if scanner.at != scanner.text.len() {
        return Err("has text after its JSON".into());
    }
    // The scanner is lenient about some details; the real parser has the last word.
    serde_json::from_str::<Json>(text).map_err(|_| "isn't valid JSON".to_owned())?;
    Ok(scanner.out)
}

/// `text` with the value at each path replaced by its new value (written compactly). The result is checked
/// to parse back to exactly what was intended.
pub fn replace(text: &str, changes: &[(Vec<String>, Json)]) -> Result<String, String> {
    let all = leaves(text)?;
    let mut edits: Vec<(usize, usize, String)> = Vec::new();
    for (path, value) in changes {
        for leaf in all.iter().filter(|l| &l.path == path) {
            edits.push((leaf.start, leaf.end, serde_json::to_string(value).map_err(|e| e.to_string())?));
        }
    }
    edits.sort_by_key(|e| std::cmp::Reverse(e.0));
    let mut out = text.to_owned();
    for (start, end, new) in edits {
        out.replace_range(start..end, &new);
    }
    let mut expected: Json = serde_json::from_str(text).map_err(|_| "isn't valid JSON".to_owned())?;
    for (path, value) in changes {
        if let Some(slot) = pointer_mut(&mut expected, path) {
            *slot = value.clone();
        }
    }
    let got: Json = serde_json::from_str(&out).map_err(|_| "the changed file wouldn't be valid JSON".to_owned())?;
    if got != expected {
        return Err("the changed file wouldn't read back as intended".into());
    }
    Ok(out)
}

/// The value at a path of object keys.
pub fn get<'a>(root: &'a Json, path: &[String]) -> Option<&'a Json> {
    path.iter().try_fold(root, |node, key| node.as_object()?.get(key))
}

fn pointer_mut<'a>(root: &'a mut Json, path: &[String]) -> Option<&'a mut Json> {
    path.iter().try_fold(root, |node, key| node.as_object_mut()?.get_mut(key))
}

struct Scanner<'a> {
    text: &'a [u8],
    at: usize,
    out: Vec<Leaf>,
}

impl Scanner<'_> {
    fn peek(&self) -> Option<u8> {
        self.text.get(self.at).copied()
    }

    fn ws(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.at += 1;
        }
    }

    fn expect(&mut self, b: u8) -> Result<(), String> {
        self.ws();
        if self.peek() == Some(b) {
            self.at += 1;
            Ok(())
        } else {
            Err("isn't valid JSON".into())
        }
    }

    fn value(&mut self, path: &mut Vec<String>) -> Result<(), String> {
        self.ws();
        let start = self.at;
        match self.peek() {
            Some(b'{') => {
                self.at += 1;
                self.ws();
                if self.peek() == Some(b'}') {
                    self.at += 1;
                    return Ok(());
                }
                loop {
                    self.ws();
                    let key_start = self.at;
                    self.string()?;
                    let key: String = serde_json::from_slice(&self.text[key_start..self.at]).map_err(|_| "isn't valid JSON".to_owned())?;
                    self.expect(b':')?;
                    path.push(key);
                    self.value(path)?;
                    path.pop();
                    self.ws();
                    match self.peek() {
                        Some(b',') => self.at += 1,
                        Some(b'}') => {
                            self.at += 1;
                            return Ok(());
                        }
                        _ => return Err("isn't valid JSON".into()),
                    }
                }
            }
            Some(b'[') => {
                self.skip_array()?;
                self.out.push(Leaf { path: path.clone(), start, end: self.at });
                Ok(())
            }
            Some(b'"') => {
                self.string()?;
                self.out.push(Leaf { path: path.clone(), start, end: self.at });
                Ok(())
            }
            Some(_) => {
                while matches!(self.peek(), Some(b) if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'+' | b'.')) {
                    self.at += 1;
                }
                if self.at == start {
                    return Err("isn't valid JSON".into());
                }
                self.out.push(Leaf { path: path.clone(), start, end: self.at });
                Ok(())
            }
            None => Err("isn't valid JSON".into()),
        }
    }

    fn string(&mut self) -> Result<(), String> {
        if self.peek() != Some(b'"') {
            return Err("isn't valid JSON".into());
        }
        self.at += 1;
        while let Some(b) = self.peek() {
            self.at += 1;
            match b {
                b'\\' => self.at += 1,
                b'"' => return Ok(()),
                _ => {}
            }
        }
        Err("isn't valid JSON".into())
    }

    fn skip_array(&mut self) -> Result<(), String> {
        let mut depth = 0usize;
        while let Some(b) = self.peek() {
            match b {
                b'"' => {
                    self.string()?;
                    continue;
                }
                b'[' | b'{' => depth += 1,
                b']' | b'}' => {
                    depth -= 1;
                    if depth == 0 {
                        self.at += 1;
                        return Ok(());
                    }
                }
                _ => {}
            }
            self.at += 1;
        }
        Err("isn't valid JSON".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const SODIUM: &str = include_str!("../testdata/sodium-options.json");

    fn path(p: &str) -> Vec<String> {
        p.split('.').map(str::to_owned).collect()
    }

    #[test]
    fn leaves_are_found_with_their_paths() {
        let found = leaves(SODIUM).unwrap();
        let paths: Vec<String> = found.iter().map(|l| l.path.join(".")).collect();
        assert!(paths.contains(&"quality.pixel_filtering_mode".to_owned()));
        assert!(paths.contains(&"performance.chunk_builder_threads".to_owned()));
        let mode = found.iter().find(|l| l.path == path("quality.pixel_filtering_mode")).unwrap();
        assert_eq!(&SODIUM[mode.start..mode.end], "\"NEAREST\"");
        assert!(leaves("[1,2]").is_err());
        assert!(leaves("{\"a\": 1,}").is_err());
        assert!(leaves("{\"a\": 1} x").is_err());
    }

    #[test]
    fn a_replacement_keeps_everything_else_byte_for_byte() {
        let out = replace(SODIUM, &[(path("performance.chunk_builder_threads"), json!(4)), (path("quality.pixel_filtering_mode"), json!("LINEAR"))]).unwrap();
        assert_eq!(out, SODIUM.replace("\"chunk_builder_threads\": 0", "\"chunk_builder_threads\": 4").replace("\"NEAREST\"", "\"LINEAR\""));
        let text = "{ \"list\" : [ \"a\" , \"b\" ], \"s\": \"x\\\"y\" }";
        let out = replace(text, &[(path("list"), json!(["c"]))]).unwrap();
        assert_eq!(out, "{ \"list\" : [\"c\"], \"s\": \"x\\\"y\" }");
        assert_eq!(replace(text, &[]).unwrap(), text);
    }
}
