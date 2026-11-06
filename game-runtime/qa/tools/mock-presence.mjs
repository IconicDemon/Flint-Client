// Dev-only: a stand-in for Flint presence (Backend migration 20261031005000_flint_presence) on this computer, for checking Flint
// marks over other players in a real game without Flint's servers or any account. It answers flint_presence_lookup exactly as
// the services do ({"<uuid>": "<mark>"} for the players asked about that are "on Flint"; Backend presence_tag) and logs every
// request's UUIDs, so a tour shows who the game asks about. Every sign-in is refused as "rate_limited" (the game backs off and
// never contacts Mojang), every other function answers {"ok": true}.
//
// usage: node qa/tools/mock-presence.mjs [--port 8788] [--tags UUID=MARK,UUID=MARK] [--log FILE] [--busy-after N]
//
// - Listens on 127.0.0.1 only. The game reaches it only from a QA-only build made with
//   FLINT_SUPABASE_URL=http://127.0.0.1:8788 FLINT_SUPABASE_ANON_KEY=flint-qa-stub (flint-services.gradle allows loopback
//   http); never ship such a build. Services on loopback are asked about offline-mode UUIDs too (PresenceTargets).
// - --tags: who is "on Flint", with their marks (default, content_creator, staff, developer, co_owner, owner).
// - --log: one JSON line per request ({at, fn, uuids, answered}) appended to FILE as well as printed.
// - --busy-after: from the Nth lookup on, answer as the services' rate limit does (400 rate_limited).
import fs from "node:fs";
import http from "node:http";

const args = process.argv.slice(2);
const option = (name, fallback) => {
	const i = args.indexOf(name);
	return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const port = Number(option("--port", "8788"));
const logFile = option("--log", null);
const busyAfter = Number(option("--busy-after", "0"));
const tags = new Map();
for (const pair of (option("--tags", "") || "").split(",").filter(Boolean)) {
	const [uuid, tag] = pair.split("=");
	if (!/^[0-9a-f-]{36}$/i.test(uuid) || !/^[a-z_]{1,32}$/.test(tag || "")) throw new Error("bad --tags entry: " + pair);
	tags.set(uuid.toLowerCase(), tag);
}
let lookups = 0;

function record(entry) {
	const line = JSON.stringify({ at: new Date().toISOString(), ...entry });
	console.log(line);
	if (logFile) fs.appendFileSync(logFile, line + "\n");
}

const server = http.createServer((req, res) => {
	let body = "";
	req.on("data", (chunk) => {
		body += chunk;
		if (body.length > 1 << 20) req.destroy();
	});
	req.on("end", () => {
		const fn = (req.url || "").split("?")[0].replace(/^\/rest\/v1\/rpc\//, "");
		let parsed = {};
		try {
			parsed = body ? JSON.parse(body) : {};
		} catch {
			// Answered as the services would answer garbage.
		}
		let status = 200;
		let answer;
		if (fn === "flint_presence_lookup") {
			lookups++;
			const asked = Array.isArray(parsed.p_uuids) ? parsed.p_uuids.map((u) => String(u).toLowerCase()) : [];
			if (busyAfter > 0 && lookups >= busyAfter) {
				status = 400;
				answer = { code: "P0001", message: "rate_limited", hint: "Too many lookups; try again in a minute." };
			} else {
				answer = {};
				for (const uuid of asked.slice(0, 200)) if (tags.has(uuid)) answer[uuid] = tags.get(uuid);
			}
			record({ fn, n: lookups, uuids: asked, answered: answer });
		} else if (fn === "flint_signin_challenge") {
			answer = { ok: false, error: "rate_limited" };
			record({ fn });
		} else {
			answer = { ok: true };
			record({ fn });
		}
		const text = JSON.stringify(answer);
		res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(text) });
		res.end(text);
	});
});
server.listen(port, "127.0.0.1", () => console.log(`mock presence on http://127.0.0.1:${port} (${tags.size} players on Flint)`));
