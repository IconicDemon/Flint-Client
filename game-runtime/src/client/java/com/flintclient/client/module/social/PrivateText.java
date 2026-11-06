package com.flintclient.client.module.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friends' credential guard, as the Flint app's ({@code app/src/lib/credentials.ts}, ported rule for rule): before a message
 * goes out, the game looks for what should never be sent in a chat, even to a friend (an email address, a password, a login
 * for another site, a card number, a key or token, a bank account), so it can ask first. Everything runs here; nothing is
 * sent anywhere. It errs a little on the side of asking: the player can always send anyway. Platform-free.
 */
public final class PrivateText {
	public enum Kind {
		EMAIL, PASSWORD, LOGIN, CARD, TOKEN, IBAN
	}

	/** A part of the text, from {@code start} up to (not including) {@code end}. */
	public record Finding(Kind kind, int start, int end) {
	}

	/** A piece of a message, private or not (for showing the private ones hidden). */
	public record Part(String text, boolean hidden) {
	}

	private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

	private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[\\p{L}]{2,}");

	/* A password named as one: "password: …", "pass=…", "pw …", "my password is …", in the languages Flint speaks. */
	private static final String PASSWORD_WORDS = "passwords?|passwort|passwd|passcode|pass|pwd|pw|contrase(?:ñ|n)a|senha|mot de passe|mdp|has(?:ł|l)o|пароль";
	private static final Pattern PASSWORD = Pattern.compile("(?<![\\p{L}\\p{N}])(" + PASSWORD_WORDS
		+ ")(?![\\p{L}\\p{N}])(\\s*(?:[:=]|-+>?)\\s*|\\s+(?:(?:is|ist|est|es|é|это)\\s+)?)([^\\s,;]+)", CI);

	/** Words that follow "password is" in ordinary talk. */
	private static final Set<String> NOT_A_PASSWORD = Set.of(("a an the too so very not wrong right correct incorrect weak strong safe secret same different "
		+ "changed reset expired long short simple easy hard required needed missing forgotten lost here there what why how this that it").split(" "));

	/** Something like "user:Secret1": a name, a colon, and a password-looking word. */
	private static final Pattern LOGIN = Pattern.compile("(?<![\\p{L}\\p{N}._@+-])([\\p{L}\\p{N}._@+-]{3,64}):(?!//)([^\\s:]{4,64})");

	/** A username named as one ("username: …"): always private; weaker labels only beside a password. */
	private static final Pattern USERNAME = Pattern.compile("(?<![\\p{L}\\p{N}])(user ?name|login|benutzername|usuario|utilisateur|użytkownik|логин|user|account|acc|alt|ign)"
		+ "(?![\\p{L}\\p{N}])\\s*(?:[:=]|-+>?)\\s*([\\p{L}\\p{N}._@+-]{3,64})", CI);
	private static final Pattern STRONG_USERNAME = Pattern.compile("^(user ?name|login|benutzername|usuario|utilisateur|użytkownik|логин)$", CI);

	/** The word just before a password ("kestrel_alt / password: …"): the account it belongs to. */
	private static final Pattern BEFORE_PASSWORD = Pattern.compile("([\\p{L}\\p{N}._@+-]{3,64})\\s*(?:/|,|\\||&|\\band\\b|\\bund\\b|\\bet\\b|\\by\\b|\\bi\\b)\\s*$");
	private static final Set<String> NOT_A_NAME = Set.of("my the his her their your our its with and alt account login user".split(" "));

	/** 13 to 19 digits, in groups with single spaces or dashes. */
	private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");

	private static final Pattern[] TOKENS = {
		// A JSON Web Token.
		Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
		// Keys with a well-known prefix.
		Pattern.compile("\\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{12,}|\\bgh[pousr]_[A-Za-z0-9]{20,}|\\bxox[abpr]-[A-Za-z0-9-]{10,}|\\bAKIA[0-9A-Z]{16}\\b"
			+ "|\\bsb_secret_[A-Za-z0-9_-]{10,}"),
		// 32 or more hex digits.
		Pattern.compile("(?<![A-Za-z0-9])[0-9a-fA-F]{32,}(?![A-Za-z0-9])"),
		// 32 or more base64 (or base64url) characters.
		Pattern.compile("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{32,}={0,2}(?![A-Za-z0-9+/_-])"),
	};

	/** A country, two check digits, then groups of four letters and digits, checked by its mod-97 sum. */
	private static final Pattern IBAN = Pattern.compile("(?<![\\p{L}\\p{N}])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![\\p{L}\\p{N}])", CI);

	private static final Pattern PASSWORDISH = Pattern.compile("\\d|[^\\p{L}\\p{N}]|.\\p{Lu}");
	private static final Pattern EXPLICIT = Pattern.compile("[:=>-]");
	private static final Pattern SENTENCE_END = Pattern.compile("[.!?]+$");
	private static final Pattern LETTER = Pattern.compile("\\p{L}");
	private static final Pattern DIGITS = Pattern.compile("^\\d+$");
	private static final Pattern A_DIGIT = Pattern.compile("\\d");
	private static final Pattern A_LATIN_LETTER = Pattern.compile("[A-Za-z]");

	private PrivateText() {
	}

	static boolean luhn(String digits) {
		int sum = 0;
		for (int i = 0; i < digits.length(); i++) {
			int d = digits.charAt(digits.length() - 1 - i) - '0';
			if (i % 2 == 1) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
		}
		return sum % 10 == 0;
	}

	static boolean ibanValid(String raw) {
		String iban = raw.replace(" ", "").toUpperCase(Locale.ROOT);
		if (iban.length() < 15 || iban.length() > 34) {
			return false;
		}
		String moved = iban.substring(4) + iban.substring(0, 4);
		int remainder = 0;
		for (int i = 0; i < moved.length(); i++) {
			char c = moved.charAt(i);
			String value = c >= 'A' && c <= 'Z' ? String.valueOf(c - 55) : String.valueOf(c);
			for (int j = 0; j < value.length(); j++) {
				int digit = value.charAt(j) - '0';
				if (digit < 0 || digit > 9) {
					return false;
				}
				remainder = (remainder * 10 + digit) % 97;
			}
		}
		return remainder == 1;
	}

	/** Looks typed as a password: a digit, a symbol or a capital after the first letter. */
	private static boolean passwordish(String word) {
		return PASSWORDISH.matcher(word).find();
	}

	/** Everything in {@code text} that looks private, in order, overlaps merged. */
	public static List<Finding> find(String text) {
		List<Finding> found = new ArrayList<>();
		Matcher m = EMAIL.matcher(text);
		while (m.find()) {
			add(found, Kind.EMAIL, m.start(), m.end());
		}

		m = PASSWORD.matcher(text);
		while (m.find()) {
			String word = m.group(1);
			String separator = m.group(2);
			String value = m.group(3);
			boolean explicit = EXPLICIT.matcher(separator).find();
			boolean said = !separator.strip().isEmpty() && !explicit;
			// After a colon or an equals sign the whole word is the secret; otherwise a full stop ends the sentence.
			String clean = explicit ? value : SENTENCE_END.matcher(value).replaceAll("");
			String lower = clean.toLowerCase(Locale.ROOT);
			if (clean.isEmpty()) {
				continue;
			}
			// "pass" alone is an ordinary word ("pass me the sword"): only "pass:" or "pass=" counts.
			if (word.equalsIgnoreCase("pass") && !explicit) {
				continue;
			}
			if (!explicit && (NOT_A_PASSWORD.contains(lower) || clean.length() < 4)) {
				continue;
			}
			if (!explicit && !said && !passwordish(clean)) {
				continue;
			}
			int start = m.start() + word.length() + separator.length();
			add(found, Kind.PASSWORD, start, start + clean.length());
			// The account the password is for, written just before it.
			Matcher before = BEFORE_PASSWORD.matcher(text.substring(0, m.start()));
			if (before.find() && !NOT_A_NAME.contains(before.group(1).toLowerCase(Locale.ROOT))) {
				add(found, Kind.LOGIN, before.start(1), before.end(1));
			}
		}

		boolean hasPassword = found.stream().anyMatch(f -> f.kind() == Kind.PASSWORD);
		m = USERNAME.matcher(text);
		while (m.find()) {
			String label = m.group(1);
			String name = m.group(2);
			if (!STRONG_USERNAME.matcher(label).matches() && !hasPassword) {
				continue;
			}
			int start = m.end() - name.length();
			add(found, Kind.LOGIN, start, start + name.length());
		}

		m = LOGIN.matcher(text);
		while (m.find()) {
			String user = m.group(1);
			String secret = m.group(2);
			if (!LETTER.matcher(user).find() || DIGITS.matcher(secret).matches()) {
				continue;
			}
			if (!(passwordish(secret) || user.contains("@"))) {
				continue;
			}
			add(found, Kind.LOGIN, m.start(), m.end());
		}

		m = CARD.matcher(text);
		while (m.find()) {
			String digits = m.group().replace(" ", "").replace("-", "");
			if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits) && !digits.matches("(\\d)\\1+")) {
				add(found, Kind.CARD, m.start(), m.end());
			}
		}

		for (Pattern pattern : TOKENS) {
			m = pattern.matcher(text);
			while (m.find()) {
				String value = m.group();
				// Long words and runs of one character aren't keys.
				if (!A_DIGIT.matcher(value).find() || !A_LATIN_LETTER.matcher(value).find()) {
					continue;
				}
				add(found, Kind.TOKEN, m.start(), m.end());
			}
		}

		m = IBAN.matcher(text);
		while (m.find()) {
			if (ibanValid(m.group())) {
				add(found, Kind.IBAN, m.start(), m.end());
			}
		}

		found.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start()) : Integer.compare(b.end(), a.end()));
		List<Finding> merged = new ArrayList<>();
		for (Finding f : found) {
			Finding last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && f.start() < last.end()) {
				merged.set(merged.size() - 1, new Finding(last.kind(), last.start(), Math.max(last.end(), f.end())));
			} else {
				merged.add(f);
			}
		}
		return merged;
	}

	private static void add(List<Finding> found, Kind kind, int start, int end) {
		if (end > start) {
			found.add(new Finding(kind, start, end));
		}
	}

	public static boolean looksPrivate(String text) {
		return !find(text).isEmpty();
	}

	/** The text in pieces, each private or not. */
	public static List<Part> parts(String text) {
		List<Part> parts = new ArrayList<>();
		int at = 0;
		for (Finding f : find(text)) {
			if (f.start() > at) {
				parts.add(new Part(text.substring(at, f.start()), false));
			}
			parts.add(new Part(text.substring(f.start(), f.end()), true));
			at = f.end();
		}
		if (at < text.length()) {
			parts.add(new Part(text.substring(at), false));
		}
		return parts;
	}
}
