/*
 * Friends' credential guard: before a message goes out, Flint looks for what should never be sent in a chat, even to a
 * friend (an email address, a password, a login for another site, a card number, a key or token, a bank account), so it
 * can ask first. Everything runs on this computer; nothing here is sent anywhere. It errs a little on the side of asking:
 * the player can always send anyway.
 */

export type FindingKind = "email" | "password" | "login" | "card" | "token" | "iban";

/** A part of the text, from `start` up to (not including) `end`. */
export type Finding = { kind: FindingKind; start: number; end: number };

const EMAIL = /[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.[\p{L}]{2,}/gu;

/*
 * A password named as one: "password: …", "pass=…", "pw …", "my password is …", in the languages Flint speaks. With a
 * colon or an equals sign anything after it counts; after "is" (or nothing) it has to look like one.
 */
const PASSWORD_WORDS = "passwords?|passwort|passwd|passcode|pass|pwd|pw|contrase(?:ñ|n)a|senha|mot de passe|mdp|has(?:ł|l)o|пароль";
const PASSWORD = new RegExp(
  String.raw`(?<![\p{L}\p{N}])(${PASSWORD_WORDS})(?![\p{L}\p{N}])(\s*(?:[:=]|-+>?)\s*|\s+(?:(?:is|ist|est|es|é|это)\s+)?)([^\s,;]+)`,
  "giu",
);

/** Words that follow "password is" in ordinary talk. */
const NOT_A_PASSWORD = new Set(
  "a an the too so very not wrong right correct incorrect weak strong safe secret same different changed reset expired long short simple easy hard required needed missing forgotten lost here there what why how this that it".split(" "),
);

/** Something like "user:Secret1": a name, a colon, and a password-looking word. */
const LOGIN = /(?<![\p{L}\p{N}._@+-])([\p{L}\p{N}._@+-]{3,64}):(?!\/\/)([^\s:]{4,64})/gu;

/**
 * A username named as one ("username: …", "login = …"): always private. Weaker labels ("user", "account", "alt", "acc",
 * "ign") only count in a message that also carries a password, since friends share Minecraft names all the time.
 */
const USERNAME = /(?<![\p{L}\p{N}])(user ?name|login|benutzername|usuario|utilisateur|użytkownik|логин|user|account|acc|alt|ign)(?![\p{L}\p{N}])\s*(?:[:=]|-+>?)\s*([\p{L}\p{N}._@+-]{3,64})/giu;
const STRONG_USERNAME = /^(user ?name|login|benutzername|usuario|utilisateur|użytkownik|логин)$/i;

/** The word just before a password ("kestrel_alt / password: …", "kestrel_alt, pw …"): the account it belongs to. */
const BEFORE_PASSWORD = /([\p{L}\p{N}._@+-]{3,64})\s*(?:\/|,|\||&|\band\b|\bund\b|\bet\b|\by\b|\bi\b)\s*$/u;
const NOT_A_NAME = new Set("my the his her their your our its with and alt account login user".split(" "));

/** 13 to 19 digits, in groups with single spaces or dashes. */
const CARD = /(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)/g;

const TOKENS = [
  // A JSON Web Token.
  /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g,
  // Keys with a well-known prefix.
  /\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{12,}|\bgh[pousr]_[A-Za-z0-9]{20,}|\bxox[abpr]-[A-Za-z0-9-]{10,}|\bAKIA[0-9A-Z]{16}\b|\bsb_secret_[A-Za-z0-9_-]{10,}/g,
  // 32 or more hex digits.
  /(?<![A-Za-z0-9])[0-9a-fA-F]{32,}(?![A-Za-z0-9])/g,
  // 32 or more base64 (or base64url) characters, with letters and digits in them.
  /(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{32,}={0,2}(?![A-Za-z0-9+/_-])/g,
];

/** A country, two check digits, then groups of four letters and digits (spaced or not), checked by its mod-97 sum. */
const IBAN = /(?<![\p{L}\p{N}])[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![\p{L}\p{N}])/giu;

function luhn(digits: string): boolean {
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    let d = digits.charCodeAt(digits.length - 1 - i) - 48;
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}

function ibanValid(raw: string): boolean {
  const iban = raw.replace(/ /g, "").toUpperCase();
  if (iban.length < 15 || iban.length > 34) return false;
  const moved = iban.slice(4) + iban.slice(0, 4);
  let remainder = 0;
  for (const c of moved) {
    const value = c >= "A" && c <= "Z" ? String(c.charCodeAt(0) - 55) : c;
    for (const digit of value) remainder = (remainder * 10 + Number(digit)) % 97;
  }
  return remainder === 1;
}

/** Looks typed as a password: a digit, a symbol or a capital after the first letter. */
const passwordish = (word: string) => /\d|[^\p{L}\p{N}]|.\p{Lu}/u.test(word);

function matches(pattern: RegExp, text: string): RegExpExecArray[] {
  pattern.lastIndex = 0;
  return Array.from(text.matchAll(pattern));
}

/** Everything in `text` that looks private, in order, overlaps merged. */
export function findPrivate(text: string): Finding[] {
  const found: Finding[] = [];
  const add = (kind: FindingKind, start: number, end: number) => end > start && found.push({ kind, start, end });

  for (const m of matches(EMAIL, text)) add("email", m.index, m.index + m[0].length);

  for (const m of matches(PASSWORD, text)) {
    const [, word, separator, value] = m;
    const explicit = /[:=>-]/.test(separator);
    const said = separator.trim().length > 0 && !explicit;
    // After a colon or an equals sign the whole word is the secret; otherwise a full stop ends the sentence.
    const clean = explicit ? value : value.replace(/[.!?]+$/, "");
    const lower = clean.toLowerCase();
    if (!clean) continue;
    // "pass" alone is an ordinary word ("pass me the sword"): only "pass:" or "pass=" counts.
    if (/^pass$/i.test(word) && !explicit) continue;
    if (!explicit && (NOT_A_PASSWORD.has(lower) || clean.length < 4)) continue;
    if (!explicit && !said && !passwordish(clean)) continue;
    const start = m.index + word.length + separator.length;
    add("password", start, start + clean.length);
    // The account the password is for, written just before it.
    const before = BEFORE_PASSWORD.exec(text.slice(0, m.index));
    if (before && !NOT_A_NAME.has(before[1].toLowerCase())) add("login", before.index, before.index + before[1].length);
  }

  const hasPassword = found.some((f) => f.kind === "password");
  for (const m of matches(USERNAME, text)) {
    const [, label, name] = m;
    if (!STRONG_USERNAME.test(label) && !hasPassword) continue;
    const start = m.index + m[0].length - name.length;
    add("login", start, start + name.length);
  }

  for (const m of matches(LOGIN, text)) {
    const [, user, secret] = m;
    if (!/\p{L}/u.test(user) || /^\d+$/.test(secret)) continue;
    if (!(passwordish(secret) || user.includes("@"))) continue;
    add("login", m.index, m.index + m[0].length);
  }

  for (const m of matches(CARD, text)) {
    const digits = m[0].replace(/[ -]/g, "");
    if (digits.length >= 13 && digits.length <= 19 && luhn(digits) && !/^(\d)\1+$/.test(digits)) add("card", m.index, m.index + m[0].length);
  }

  for (const pattern of TOKENS) {
    for (const m of matches(pattern, text)) {
      const value = m[0];
      // Long words and runs of one character aren't keys.
      if (!/\d/.test(value) || !/[A-Za-z]/.test(value)) continue;
      add("token", m.index, m.index + value.length);
    }
  }

  for (const m of matches(IBAN, text)) {
    if (ibanValid(m[0])) add("iban", m.index, m.index + m[0].length);
  }

  found.sort((a, b) => a.start - b.start || b.end - a.end);
  const merged: Finding[] = [];
  for (const f of found) {
    const last = merged[merged.length - 1];
    if (last && f.start < last.end) last.end = Math.max(last.end, f.end);
    else merged.push({ ...f });
  }
  return merged;
}

export const looksPrivate = (text: string) => findPrivate(text).length > 0;

/** The text in pieces, each private or not, for showing the private ones blurred. */
export function privateParts(text: string): { text: string; hidden: boolean }[] {
  const parts: { text: string; hidden: boolean }[] = [];
  let at = 0;
  for (const f of findPrivate(text)) {
    if (f.start > at) parts.push({ text: text.slice(at, f.start), hidden: false });
    parts.push({ text: text.slice(f.start, f.end), hidden: true });
    at = f.end;
  }
  if (at < text.length) parts.push({ text: text.slice(at), hidden: false });
  return parts;
}
