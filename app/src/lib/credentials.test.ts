// Run with `npm test`. Friends' credential guard: what makes Flint ask before a message goes out.
import assert from "node:assert/strict";
import { test } from "node:test";
import { findPrivate, looksPrivate, privateParts, type FindingKind } from "./credentials.ts";

/** The private parts of `text`, as [kind, text]. */
const found = (text: string): [FindingKind, string][] => findPrivate(text).map((f) => [f.kind, text.slice(f.start, f.end)]);

test("ordinary chat is never stopped", () => {
  for (const text of [
    "gg wp, want to play bedwars at 8?",
    "pass me the sword",
    "I'll pass on that one",
    "my password reset email never came",
    "passwords are annoying lol",
    "what's your password for the realm? jk",
    "join play.hypixel.net:25565 at 10:30",
    "check https://modrinth.com/mod/sodium and https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "my score is 1234567 now",
    "note:remember the diamonds",
    "the seed is -4530634556500121041",
    "call me at +1 555 123 4567",
    "4111 1111 1111 1112",
    "DE89 3704 0044 0532 0130 01",
    "C:/Users/steve/AppData/Roaming/.minecraft/saves",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "mods/sodium-fabric-0.6.12+mc26.2.jar",
  ]) {
    assert.deepEqual(found(text), [], text);
  }
});

test("email addresses", () => {
  assert.deepEqual(found("mail me at steve.miner+mc@example.co.uk thanks"), [["email", "steve.miner+mc@example.co.uk"]]);
  assert.deepEqual(found("ÉLODIE@exemple.fr"), [["email", "ÉLODIE@exemple.fr"]]);
});

test("passwords named as one", () => {
  assert.deepEqual(found("password: hunter2"), [["password", "hunter2"]]);
  assert.deepEqual(found("Password=abc"), [["password", "abc"]]);
  assert.deepEqual(found("pass=x9!"), [["password", "x9!"]]);
  assert.deepEqual(found("pass: 1234"), [["password", "1234"]]);
  assert.deepEqual(found("pw hunter2"), [["password", "hunter2"]]);
  assert.deepEqual(found("my pw is Sunset"), [["password", "Sunset"]]);
  assert.deepEqual(found("my password is pineapple."), [["password", "pineapple"]]);
  assert.deepEqual(found("ok the password -> Tr0ub4dor&3, don't share"), [["password", "Tr0ub4dor&3"]]);
  assert.deepEqual(found("mein Passwort ist sommer2026"), [["password", "sommer2026"]]);
  assert.deepEqual(found("mi contraseña es gatito99"), [["password", "gatito99"]]);
  assert.deepEqual(found("мой пароль это qwerty123"), [["password", "qwerty123"]]);
  assert.deepEqual(found("my password is too long"), [], "an ordinary word after it");
  assert.deepEqual(found("password lol"), [], "nothing typed like a password");
});

test("logins for other sites", () => {
  assert.deepEqual(found("alt account steve_alt:Sunset42"), [["login", "steve_alt:Sunset42"]]);
  assert.deepEqual(found("use me@mail.com:hunter22"), [["login", "me@mail.com:hunter22"]], "an email with a password is one finding");
});

test("card numbers pass the Luhn check", () => {
  assert.deepEqual(found("card 4111 1111 1111 1111 exp 12/29"), [["card", "4111 1111 1111 1111"]]);
  assert.deepEqual(found("5555-5555-5555-4444"), [["card", "5555-5555-5555-4444"]]);
  assert.deepEqual(found("378282246310005"), [["card", "378282246310005"]]);
  assert.deepEqual(found("0000 0000 0000 0000"), [], "one digit repeated");
});

test("keys and tokens", () => {
  const jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
  assert.deepEqual(found(`token ${jwt}`), [["token", jwt]]);
  assert.deepEqual(found("8667ba71b85a4004af54457a9734eed78667ba71"), [["token", "8667ba71b85a4004af54457a9734eed78667ba71"]]);
  assert.deepEqual(found("key: dGhpcyBpcyBhIHNlY3JldCBrZXkgZm9yIHRlc3RzMTIz=="), [["token", "dGhpcyBpcyBhIHNlY3JldCBrZXkgZm9yIHRlc3RzMTIz=="]]);
  assert.deepEqual(found("ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"), [["token", "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"]]);
  assert.deepEqual(found("sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc"), [["token", "sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc"]]);
});

test("bank accounts (IBAN) with a valid checksum", () => {
  assert.deepEqual(found("send it to DE89 3704 0044 0532 0130 00 please"), [["iban", "DE89 3704 0044 0532 0130 00"]]);
  assert.deepEqual(found("GB82WEST12345698765432"), [["iban", "GB82WEST12345698765432"]]);
  assert.deepEqual(found("fr14 2004 1010 0505 0001 3m02 606"), [["iban", "fr14 2004 1010 0505 0001 3m02 606"]]);
});

test("the private parts split out for blurring, overlaps merged", () => {
  const text = "hi! password: hunter2 and mail a@b.io";
  assert.ok(looksPrivate(text));
  assert.deepEqual(privateParts(text), [
    { text: "hi! password: ", hidden: false },
    { text: "hunter2", hidden: true },
    { text: " and mail ", hidden: false },
    { text: "a@b.io", hidden: true },
  ]);
  assert.deepEqual(privateParts("nothing here"), [{ text: "nothing here", hidden: false }]);
  assert.equal(looksPrivate(""), false);
  // Several patterns over the same text are one part.
  assert.equal(findPrivate("pw: 4111111111111111").length, 1);
});

test("the account a password belongs to is hidden with it, and a named username always", () => {
  assert.deepEqual(found("sure, log in with my alt: kestrel_alt / password: Emerald77!"), [
    ["login", "kestrel_alt"],
    ["password", "Emerald77!"],
  ]);
  assert.deepEqual(found("Steve_Builds, pw Hunter2!"), [
    ["login", "Steve_Builds"],
    ["password", "Hunter2"],
  ]);
  assert.deepEqual(found("username: Kestrel_Main"), [["login", "Kestrel_Main"]]);
  assert.deepEqual(found("login = kestrel.main"), [["login", "kestrel.main"]]);
  // Weaker labels only with a password in the same message: friends share Minecraft names all the time.
  assert.deepEqual(found("my ign: Kestrel, add me"), []);
  assert.deepEqual(found("my alt: Kestrel_Alt"), []);
  assert.deepEqual(found("account: Kestrel_Alt password: Emerald77"), [
    ["login", "Kestrel_Alt"],
    ["password", "Emerald77"],
  ]);
});
