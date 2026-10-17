/** Reading Minecraft logs and crash reports for the profile Logs view. Pure functions, no I/O. */

export type Level = "error" | "warn" | "info" | "debug";
export type LogLine = { n: number; time: string | null; text: string; level: Level; stack: boolean };

const TIME = /^\[(\d{2}:\d{2}:\d{2})\]\s?/;
const STACK = /^\s+at |^\s*Caused by:|^\s*\.\.\. \d+ more|^\s+Suppressed:/;
/** A thrown exception's first line, e.g. `java.lang.IllegalStateException: boom`. */
const EXCEPTION = /^[\w$.]+(Exception|Error|Throwable)(:|$)/;

function levelOf(text: string): Level | null {
  if (/\/(ERROR|FATAL)\]|^\s*(FATAL|ERROR)\b|Exception in thread|^---- Minecraft Crash Report ----|^Description:/.test(text)) return "error";
  if (/\/WARN(ING)?\]|^\s*WARN(ING)?\b/.test(text)) return "warn";
  if (/\/(DEBUG|TRACE)\]/.test(text)) return "debug";
  if (/\/INFO\]/.test(text)) return "info";
  return null;
}

/**
 * Splits a log into lines with their time and level. Stack frames and indented continuation lines
 * belong to the entry above them (so filtering to errors keeps whole traces); exception lines are
 * errors; any other unlabelled line starts afresh as ordinary output.
 */
export function parseLog(text: string): LogLine[] {
  const out: LogLine[] = [];
  let current: Level = "info";
  const lines = text.split("\n");
  if (lines.length && lines[lines.length - 1] === "") lines.pop();
  for (const [i, raw] of lines.entries()) {
    const match = raw.match(TIME);
    const body = match ? raw.slice(match[0].length) : raw;
    const stack = STACK.test(body);
    const own = stack ? null : levelOf(body) ?? (EXCEPTION.test(body) ? "error" : null);
    const continues = stack || /^\s/.test(body);
    current = own ?? (continues ? current : "info");
    out.push({ n: i + 1, time: match ? match[1] : null, text: body, level: current, stack });
  }
  return out;
}

export type LevelFilter = "all" | "warn" | "error";

export function filterLines(lines: LogLine[], level: LevelFilter, query: string): LogLine[] {
  const q = query.trim().toLowerCase();
  return lines.filter((l) => {
    if (level === "error" && l.level !== "error") return false;
    if (level === "warn" && l.level !== "error" && l.level !== "warn") return false;
    return !q || l.text.toLowerCase().includes(q);
  });
}

/** Number of distinct warning and error entries (a stack trace counts once, with its entry). */
export function countEntries(lines: LogLine[]): { warn: number; error: number } {
  let warn = 0;
  let error = 0;
  for (const line of lines) {
    if (line.stack || !levelOf(line.text)) continue;
    if (line.level === "error") error++;
    else if (line.level === "warn") warn++;
  }
  return { warn, error };
}

/** The `Description:` line of a Minecraft crash report. */
export function crashDescription(text: string): string | null {
  const line = text.split("\n").find((l) => l.trim().startsWith("Description:"));
  const value = line?.trim().slice("Description:".length).trim();
  return value || null;
}
