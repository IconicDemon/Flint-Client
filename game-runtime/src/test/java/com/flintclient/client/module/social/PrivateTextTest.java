package com.flintclient.client.module.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.social.PrivateText.Kind;
import com.flintclient.client.module.social.PrivateText.Part;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The credential guard, against the Flint app's own cases (app/src/lib/credentials.test.ts): the game asks exactly when the app does. */
class PrivateTextTest {
	/** The private parts of {@code text}, as "KIND text". */
	private static List<String> found(String text) {
		return PrivateText.find(text).stream().map(f -> f.kind() + " " + text.substring(f.start(), f.end())).toList();
	}

	@Test
	void ordinaryChatIsNeverStopped() {
		for (String text : List.of("gg wp, want to play bedwars at 8?", "pass me the sword", "I'll pass on that one", "my password reset email never came",
			"passwords are annoying lol", "what's your password for the realm? jk", "join play.hypixel.net:25565 at 10:30",
			"check https://modrinth.com/mod/sodium and https://www.youtube.com/watch?v=dQw4w9WgXcQ", "my score is 1234567 now",
			"note:remember the diamonds", "the seed is -4530634556500121041", "call me at +1 555 123 4567", "4111 1111 1111 1112",
			"DE89 3704 0044 0532 0130 01", "C:/Users/steve/AppData/Roaming/.minecraft/saves", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"mods/sodium-fabric-0.6.12+mc26.2.jar")) {
			assertEquals(List.of(), found(text), text);
		}
	}

	@Test
	void emailAddresses() {
		assertEquals(List.of("EMAIL steve.miner+mc@example.co.uk"), found("mail me at steve.miner+mc@example.co.uk thanks"));
		assertEquals(List.of("EMAIL ÉLODIE@exemple.fr"), found("ÉLODIE@exemple.fr"));
	}

	@Test
	void passwordsNamedAsOne() {
		assertEquals(List.of("PASSWORD hunter2"), found("password: hunter2"));
		assertEquals(List.of("PASSWORD abc"), found("Password=abc"));
		assertEquals(List.of("PASSWORD x9!"), found("pass=x9!"));
		assertEquals(List.of("PASSWORD 1234"), found("pass: 1234"));
		assertEquals(List.of("PASSWORD hunter2"), found("pw hunter2"));
		assertEquals(List.of("PASSWORD Sunset"), found("my pw is Sunset"));
		assertEquals(List.of("PASSWORD pineapple"), found("my password is pineapple."));
		assertEquals(List.of("PASSWORD Tr0ub4dor&3"), found("ok the password -> Tr0ub4dor&3, don't share"));
		assertEquals(List.of("PASSWORD sommer2026"), found("mein Passwort ist sommer2026"));
		assertEquals(List.of("PASSWORD gatito99"), found("mi contraseña es gatito99"));
		assertEquals(List.of("PASSWORD qwerty123"), found("мой пароль это qwerty123"));
		assertEquals(List.of(), found("my password is too long"), "an ordinary word after it");
		assertEquals(List.of(), found("password lol"), "nothing typed like a password");
	}

	@Test
	void loginsForOtherSites() {
		assertEquals(List.of("LOGIN steve_alt:Sunset42"), found("alt account steve_alt:Sunset42"));
		assertEquals(List.of("LOGIN me@mail.com:hunter22"), found("use me@mail.com:hunter22"), "an email with a password is one finding");
	}

	@Test
	void cardNumbersPassTheLuhnCheck() {
		assertEquals(List.of("CARD 4111 1111 1111 1111"), found("card 4111 1111 1111 1111 exp 12/29"));
		assertEquals(List.of("CARD 5555-5555-5555-4444"), found("5555-5555-5555-4444"));
		assertEquals(List.of("CARD 378282246310005"), found("378282246310005"));
		assertEquals(List.of(), found("0000 0000 0000 0000"), "one digit repeated");
	}

	@Test
	void keysAndTokens() {
		String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
		assertEquals(List.of("TOKEN " + jwt), found("token " + jwt));
		assertEquals(List.of("TOKEN 8667ba71b85a4004af54457a9734eed78667ba71"), found("8667ba71b85a4004af54457a9734eed78667ba71"));
		assertEquals(List.of("TOKEN dGhpcyBpcyBhIHNlY3JldCBrZXkgZm9yIHRlc3RzMTIz=="), found("key: dGhpcyBpcyBhIHNlY3JldCBrZXkgZm9yIHRlc3RzMTIz=="));
		assertEquals(List.of("TOKEN ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"), found("ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"));
		assertEquals(List.of("TOKEN sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc"), found("sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc"));
	}

	@Test
	void bankAccountsWithAValidChecksum() {
		assertEquals(List.of("IBAN DE89 3704 0044 0532 0130 00"), found("send it to DE89 3704 0044 0532 0130 00 please"));
		assertEquals(List.of("IBAN GB82WEST12345698765432"), found("GB82WEST12345698765432"));
		assertEquals(List.of("IBAN fr14 2004 1010 0505 0001 3m02 606"), found("fr14 2004 1010 0505 0001 3m02 606"));
	}

	@Test
	void thePrivatePartsSplitOutForHidingOverlapsMerged() {
		String text = "hi! password: hunter2 and mail a@b.io";
		assertTrue(PrivateText.looksPrivate(text));
		assertEquals(List.of(new Part("hi! password: ", false), new Part("hunter2", true), new Part(" and mail ", false), new Part("a@b.io", true)),
			PrivateText.parts(text));
		assertEquals(List.of(new Part("nothing here", false)), PrivateText.parts("nothing here"));
		assertFalse(PrivateText.looksPrivate(""));
		assertEquals(1, PrivateText.find("pw: 4111111111111111").size(), "several patterns over the same text are one part");
	}

	@Test
	void theAccountAPasswordBelongsToIsHiddenWithItAndANamedUsernameAlways() {
		assertEquals(List.of("LOGIN kestrel_alt", "PASSWORD Emerald77!"), found("sure, log in with my alt: kestrel_alt / password: Emerald77!"));
		assertEquals(List.of("LOGIN Steve_Builds", "PASSWORD Hunter2"), found("Steve_Builds, pw Hunter2!"));
		assertEquals(List.of("LOGIN Kestrel_Main"), found("username: Kestrel_Main"));
		assertEquals(List.of("LOGIN kestrel.main"), found("login = kestrel.main"));
		assertEquals(List.of(), found("my ign: Kestrel, add me"), "weaker labels only beside a password");
		assertEquals(List.of(), found("my alt: Kestrel_Alt"));
		assertEquals(List.of("LOGIN Kestrel_Alt", "PASSWORD Emerald77"), found("account: Kestrel_Alt password: Emerald77"));
		assertEquals(Kind.LOGIN, PrivateText.find("username: Kestrel_Main").get(0).kind());
	}
}
