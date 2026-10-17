//! Turns what Minecraft leaves behind after a failed start (its log4j console output and any
//! crash report) into a short explanation for players plus redacted technical details.

use crate::content::redact;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::time::SystemTime;

/// The longest excerpt of technical details attached to a failure.
const DETAIL_LIMIT: usize = 4000;

/// Reads at most the last `max` bytes of a file, starting at a line boundary.
pub fn read_tail(path: &Path, max: u64) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let len = file.metadata()?.len();
    let start = len.saturating_sub(max);
    file.seek(SeekFrom::Start(start))?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;
    let text = String::from_utf8_lossy(&bytes).into_owned();
    Ok(if start > 0 {
        text.split_once('\n').map(|(_, rest)| rest.to_owned()).unwrap_or_default()
    } else {
        text
    })
}

fn unescape(text: &str) -> String {
    text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/// The text of `<log4j:{tag}>` inside an event: every CDATA section joined (log4j splits a
/// message containing `]]>` into several), or the escaped text when there is no CDATA.
fn element_text(block: &str, tag: &str) -> Option<String> {
    let open = format!("<log4j:{tag}>");
    let close = format!("</log4j:{tag}>");
    let start = block.find(&open)? + open.len();
    let end = start + block[start..].find(&close)?;
    let inner = &block[start..end];
    if !inner.contains("<![CDATA[") {
        return Some(unescape(inner));
    }
    let mut out = String::new();
    let mut rest = inner;
    while let Some(s) = rest.find("<![CDATA[") {
        let body = &rest[s + 9..];
        let Some(e) = body.find("]]>") else { break };
        out.push_str(&body[..e]);
        rest = &body[e + 3..];
    }
    Some(out)
}

fn attribute(head: &str, name: &str) -> Option<String> {
    let key = format!(" {name}=\"");
    let start = head.find(&key)? + key.len();
    let end = start + head[start..].find('"')?;
    Some(unescape(&head[start..end]))
}

fn push_plain(out: &mut String, text: &str) {
    for line in text.lines().map(str::trim_end).filter(|l| !l.trim().is_empty()) {
        out.push_str(line);
        out.push('\n');
    }
}

/// Converts log4j XML console output into ordinary `[thread/LEVEL] logger: message` lines.
/// Anything outside an XML event (for example JVM errors) passes through unchanged, and an event
/// cut off by a crash is dropped rather than shown as raw XML.
pub fn render_log4j(text: &str) -> String {
    const END: &str = "</log4j:Event>";
    let mut out = String::with_capacity(text.len() / 2);
    let mut rest = text;
    while let Some(start) = rest.find("<log4j:Event") {
        push_plain(&mut out, &rest[..start]);
        let Some(length) = rest[start..].find(END) else {
            rest = "";
            break;
        };
        let block = &rest[start..start + length];
        let head = &block[..block.find('>').unwrap_or(block.len())];
        let level = attribute(head, "level").unwrap_or_else(|| "INFO".into());
        let thread = attribute(head, "thread").unwrap_or_default();
        let logger = attribute(head, "logger").map(|l| l.rsplit('.').next().unwrap_or_default().to_owned()).unwrap_or_default();
        let message = element_text(block, "Message").unwrap_or_default();
        out.push_str(&format!("[{thread}/{level}]"));
        if !logger.is_empty() {
            out.push_str(&format!(" {logger}"));
        }
        out.push_str(": ");
        out.push_str(message.trim_end());
        out.push('\n');
        if let Some(throwable) = element_text(block, "Throwable") {
            push_plain(&mut out, &throwable);
        }
        rest = &rest[start + length + END.len()..];
    }
    push_plain(&mut out, rest);
    out
}

/// Known causes of failed starts, matched against the log and crash report. The first match wins.
const KNOWN: &[(&[&str], &str)] = &[
    (
        &["Could not reserve enough space for object heap", "Invalid maximum heap size", "Initial heap size set to a larger value"],
        "Java couldn't reserve the memory set in Settings. Lower the memory setting and try again.",
    ),
    (&["java.lang.OutOfMemoryError"], "Minecraft ran out of memory. Give it more memory in Settings, or remove some mods."),
    (
        &["UnsupportedClassVersionError", "compiled by a more recent version of the Java Runtime"],
        "The Java runtime is too old for this version of Minecraft or one of its mods. Use Flint's managed Java or a newer Java.",
    ),
    (
        &["Incompatible mods found", "Incompatible mod set", "Mod resolution failed", "requires version", "Missing dependency"],
        "Fabric found mods that can't run together or are missing a dependency. Open the log to see which ones, then update or remove them.",
    ),
    (
        &["Mixin apply failed", "MixinApplyError", "InvalidMixinException", "MixinTransformerError"],
        "A mod failed to load. It may not support this Minecraft version; try switching off recently added mods.",
    ),
    (
        &["GLFW error", "Failed to create the GLFW window", "Pixel format not accelerated", "Couldn't set pixel format", "WGL: The driver does not appear to support OpenGL", "Unable to initialise OpenGL"],
        "Minecraft couldn't start its graphics. Make sure your graphics drivers are up to date.",
    ),
    (&["Could not create the Java Virtual Machine", "Unrecognized option"], "Java refused the launch settings. Check the custom Java path and memory in Settings."),
];

/// The `Description:` line of a Minecraft crash report.
pub fn crash_description(report: &str) -> Option<String> {
    report
        .lines()
        .find_map(|l| l.trim().strip_prefix("Description:"))
        .map(|d| d.trim().to_owned())
        .filter(|d| !d.is_empty())
}

/// A short, player-facing explanation of why the game stopped.
pub fn summarize(log: &str, crash: Option<&str>, exit: Option<i32>, during_startup: bool) -> String {
    let haystack = |needle: &str| log.contains(needle) || crash.is_some_and(|c| c.contains(needle));
    for (needles, explanation) in KNOWN {
        if needles.iter().any(|n| haystack(n)) {
            return (*explanation).to_owned();
        }
    }
    let how = match exit {
        Some(code) => format!("exit code {code}"),
        None => "stopped by the system".to_owned(),
    };
    match crash.and_then(crash_description) {
        Some(description) => format!("Minecraft crashed: {description} ({how})."),
        None if during_startup => format!("Minecraft closed while starting ({how})."),
        None => format!("Minecraft closed unexpectedly ({how})."),
    }
}

/// The most useful lines for someone diagnosing a failure: the last errors and stack traces in
/// the log, preceded by the crash report's description. Tokens are redacted.
pub fn details(log: &str, crash: Option<(&str, &str)>) -> String {
    let lines: Vec<&str> = log.lines().collect();
    let interesting = |l: &str| {
        l.contains("/ERROR]") || l.contains("/FATAL]") || l.contains("/WARN]") || l.starts_with("\tat ") || l.starts_with("Caused by") || l.contains("Exception") || l.contains("Error:")
    };
    let mut picked: Vec<&str> = lines.iter().copied().filter(|l| interesting(l)).collect();
    if picked.is_empty() {
        picked = lines.iter().rev().take(25).rev().copied().collect();
    }
    let mut out = String::new();
    if let Some((name, report)) = crash {
        out.push_str(&format!("Crash report: {name}\n"));
        if let Some(description) = crash_description(report) {
            out.push_str(&format!("Description: {description}\n"));
        }
        out.push('\n');
    }
    for line in picked.iter().rev().take(40).collect::<Vec<_>>().into_iter().rev() {
        out.push_str(line);
        out.push('\n');
    }
    let mut out = redact(&out);
    if out.len() > DETAIL_LIMIT {
        let cut = out.len() - DETAIL_LIMIT;
        let boundary = (cut..out.len()).find(|i| out.is_char_boundary(*i)).unwrap_or(out.len());
        out = format!("…\n{}", &out[boundary..]);
    }
    out
}

/// The newest crash report written since `since`, as (file name, text).
pub fn new_crash_report(game_dir: &Path, since: SystemTime) -> Option<(String, String)> {
    let entries = std::fs::read_dir(game_dir.join("crash-reports")).ok()?;
    let newest = entries
        .flatten()
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e)))
        .filter(|(modified, _)| *modified >= since)
        .max_by_key(|(modified, _)| *modified)?;
    let mut text = String::new();
    std::fs::File::open(newest.1.path()).ok()?.take(256 * 1024).read_to_string(&mut text).ok()?;
    Some((newest.1.file_name().to_string_lossy().into_owned(), text))
}

#[cfg(test)]
mod tests {
    use super::*;

    const XML: &str = r#"<log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1791917449086" level="INFO" thread="Render thread">
  <log4j:Message><![CDATA[Setting user: Steve]]></log4j:Message>
</log4j:Event>
<log4j:Event logger="FabricLoader" timestamp="1791917449087" level="ERROR" thread="main">
  <log4j:Message><![CDATA[Incompatible mods found!]]></log4j:Message>
  <log4j:Throwable><![CDATA[net.fabricmc.loader.impl.FormattedException: Mod resolution failed
	at net.fabricmc.loader.impl.FabricLoaderImpl.load(FabricLoaderImpl.java:198)
]]></log4j:Throwable>
</log4j:Event>
"#;

    #[test]
    fn renders_log4j_events_as_plain_lines() {
        let text = render_log4j(XML);
        assert!(!text.contains("<log4j"), "no XML may remain: {text}");
        assert!(text.contains("[Render thread/INFO] Minecraft: Setting user: Steve"));
        assert!(text.contains("[main/ERROR] FabricLoader: Incompatible mods found!"));
        assert!(text.contains("\tat net.fabricmc.loader.impl.FabricLoaderImpl.load"));
    }

    #[test]
    fn keeps_plain_jvm_output_and_drops_a_truncated_event() {
        let raw = "Error: Could not create the Java Virtual Machine.\n<log4j:Event logger=\"x\" level=\"INFO\" thread=\"main\">\n  <log4j:Message><![CDATA[half";
        let text = render_log4j(raw);
        assert_eq!(text, "Error: Could not create the Java Virtual Machine.\n");
    }

    #[test]
    fn joins_split_cdata_and_unescapes_text() {
        let raw = "<log4j:Event logger=\"a\" level=\"WARN\" thread=\"t\"><log4j:Message><![CDATA[a]]]]><![CDATA[>b]]></log4j:Message></log4j:Event>\n<log4j:Event logger=\"a\" level=\"INFO\" thread=\"t\"><log4j:Message>1 &lt; 2 &amp; 3</log4j:Message></log4j:Event>";
        let text = render_log4j(raw);
        assert!(text.contains(": a]]>b"), "{text}");
        assert!(text.contains(": 1 < 2 & 3"), "{text}");
    }

    #[test]
    fn explains_common_failures() {
        assert!(summarize("java.lang.OutOfMemoryError: Java heap space", None, Some(1), true).contains("ran out of memory"));
        assert!(summarize("Error: Could not reserve enough space for object heap", None, Some(1), true).contains("Lower the memory"));
        assert!(summarize(&render_log4j(XML), None, Some(1), true).contains("Fabric found mods"));
        assert!(summarize("UnsupportedClassVersionError: x has been compiled by a more recent version of the Java Runtime", None, Some(1), true).contains("too old"));
        assert!(summarize("[Render thread/ERROR]: GLFW error 65543", None, Some(1), true).contains("graphics"));
    }

    #[test]
    fn falls_back_to_crash_description_or_exit_code() {
        let report = "---- Minecraft Crash Report ----\nTime: now\nDescription: Rendering overlay\n\njava.lang.NullPointerException";
        assert_eq!(summarize("", Some(report), Some(-1), false), "Minecraft crashed: Rendering overlay (exit code -1).");
        assert_eq!(summarize("", None, Some(1), true), "Minecraft closed while starting (exit code 1).");
        assert_eq!(summarize("", None, None, false), "Minecraft closed unexpectedly (stopped by the system).");
    }

    #[test]
    fn details_are_redacted_and_bounded() {
        let token = format!("eyJ{}.{}.{}", "a".repeat(30), "b".repeat(30), "c".repeat(30));
        let log = format!("[main/ERROR]: boom --accessToken {token}\n[main/ERROR]: leaked {token}\n");
        let out = details(&log, Some(("crash-1.txt", "Description: Boom")));
        assert!(!out.contains(&token), "{out}");
        assert!(out.contains("Crash report: crash-1.txt") && out.contains("Description: Boom"));
        let long = "[main/ERROR]: ".to_owned() + &"x".repeat(9000);
        assert!(details(&long, None).len() <= DETAIL_LIMIT + 8);
    }

    #[test]
    fn reads_only_the_tail_from_a_line_boundary() {
        let dir = crate::core::TestDir::new();
        let path = dir.0.join("log.txt");
        std::fs::write(&path, "first line\nsecond line\nthird line\n").unwrap();
        assert_eq!(read_tail(&path, 1024).unwrap(), "first line\nsecond line\nthird line\n");
        assert_eq!(read_tail(&path, 15).unwrap(), "third line\n");
    }

    #[test]
    fn finds_only_crash_reports_written_after_launch() {
        let dir = crate::core::TestDir::new();
        let reports = dir.0.join("crash-reports");
        std::fs::create_dir_all(&reports).unwrap();
        std::fs::write(reports.join("crash-old.txt"), "Description: old").unwrap();
        let since = SystemTime::now() + std::time::Duration::from_secs(3600);
        assert!(new_crash_report(&dir.0, since).is_none());
        let (name, text) = new_crash_report(&dir.0, SystemTime::UNIX_EPOCH).unwrap();
        assert_eq!(name, "crash-old.txt");
        assert_eq!(crash_description(&text).as_deref(), Some("old"));
    }
}
