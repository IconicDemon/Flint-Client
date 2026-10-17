//! Proving Minecraft ownership to Flint AI without sending any token.
//!
//! Every signed-in player gets a short-lived key pair from Minecraft services, the one Minecraft
//! uses to sign chat. Mojang signs the public half (with the player's UUID and the key's expiry).
//! Flint signs a fresh "flint-ai-session" message with the private half, on this computer, and
//! sends the gateway only the player's UUID, the public key, Mojang's signature and its own
//! signature. The gateway checks both signatures against Mojang's published keys. The Minecraft
//! access token is used only to fetch the certificate from Minecraft services, never sent elsewhere,
//! and the private key never leaves the app's memory.

use crate::core::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use rsa::pkcs1::DecodeRsaPrivateKey;
use rsa::pkcs1v15::SigningKey;
use rsa::pkcs8::DecodePrivateKey;
use rsa::signature::{SignatureEncoding, Signer};
use rsa::RsaPrivateKey;
use serde_json::{json, Value};
use sha2::Sha256;
use std::sync::{Mutex, OnceLock};

const CERTIFICATES: &str = "https://api.minecraftservices.com/player/certificates";

#[derive(Clone)]
pub struct Certificate {
    key: RsaPrivateKey,
    /// The public key as Minecraft services sent it: X.509 SubjectPublicKeyInfo DER, base64.
    public_key: String,
    /// Mojang's `publicKeySignatureV2`, base64.
    key_signature: String,
    expires_at: i64,
    refresh_after: i64,
}

/// One certificate per run, for the account it belongs to.
fn cache() -> &'static Mutex<Option<(String, Certificate)>> {
    static CACHE: OnceLock<Mutex<Option<(String, Certificate)>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(None))
}

pub fn now_millis() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// The base64 body of a PEM block, whatever its label (Minecraft services label PKCS#8 and X.509
/// keys "RSA PRIVATE KEY" / "RSA PUBLIC KEY").
fn pem_body(pem: &str) -> Option<String> {
    let body: String = pem.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with("-----")).collect();
    (!body.is_empty() && STANDARD.decode(&body).is_ok()).then_some(body)
}

fn private_key(pem: &str) -> Option<RsaPrivateKey> {
    let der = STANDARD.decode(pem_body(pem)?).ok()?;
    RsaPrivateKey::from_pkcs8_der(&der).or_else(|_| RsaPrivateKey::from_pkcs1_der(&der)).ok()
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// "2026-10-15T04:53:17.926513Z" (or with a ±hh:mm offset) → epoch milliseconds, truncating
/// sub-millisecond digits the way Java's `Instant.toEpochMilli` does.
pub fn iso_millis(text: &str) -> Option<i64> {
    let (date, rest) = text.trim().split_once('T')?;
    let mut d = date.split('-').map(|p| p.parse::<i64>().ok());
    let (y, mo, da) = (d.next()??, d.next()??, d.next()??);
    let (clock, offset_ms) = if let Some(c) = rest.strip_suffix('Z') {
        (c, 0)
    } else {
        let at = rest.rfind(['+', '-'])?;
        let (c, off) = rest.split_at(at);
        let sign = if off.starts_with('-') { -1 } else { 1 };
        let (oh, om) = off[1..].split_once(':')?;
        (c, sign * (oh.parse::<i64>().ok()? * 3_600_000 + om.parse::<i64>().ok()? * 60_000))
    };
    let (hms, frac) = clock.split_once('.').unwrap_or((clock, ""));
    let mut t = hms.split(':').map(|p| p.parse::<i64>().ok());
    let (h, mi, s) = (t.next()??, t.next()??, t.next()??);
    let millis = format!("{:0<3}", &frac[..frac.len().min(3)]).parse::<i64>().ok()?;
    if !(1..=12).contains(&mo) || !(1..=31).contains(&da) || h > 23 || mi > 59 || s > 60 {
        return None;
    }
    Some((days_from_civil(y, mo, da) * 86_400 + h * 3600 + mi * 60 + s) * 1000 + millis - offset_ms)
}

/// An HTTP `Date` header ("Wed, 14 Oct 2026 05:20:00 GMT") → epoch milliseconds.
pub fn http_date_millis(text: &str) -> Option<i64> {
    let mut parts = text.split_whitespace().skip(1);
    let day: i64 = parts.next()?.parse().ok()?;
    let name = parts.next()?;
    let month = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"].iter().position(|m| *m == name)? as i64 + 1;
    let year: i64 = parts.next()?.parse().ok()?;
    iso_millis(&format!("{year:04}-{month:02}-{day:02}T{}Z", parts.next()?))
}

impl Certificate {
    /// Parses Minecraft services' answer to POST /player/certificates.
    pub fn from_response(body: &Value) -> Option<Certificate> {
        let key = private_key(body["keyPair"]["privateKey"].as_str()?)?;
        let public_key = pem_body(body["keyPair"]["publicKey"].as_str()?)?;
        let key_signature = body["publicKeySignatureV2"].as_str().map(|s| s.trim().to_owned()).filter(|s| STANDARD.decode(s).is_ok())?;
        let expires_at = iso_millis(body["expiresAt"].as_str()?)?;
        let refresh_after = body["refreshedAfter"].as_str().and_then(iso_millis).unwrap_or(expires_at - 3_600_000);
        Some(Certificate { key, public_key, key_signature, expires_at, refresh_after })
    }

    /// The signed proof sent to the gateway's /v1/session.
    pub fn proof(&self, uuid: &str, nonce: &str, issued_at: i64) -> Value {
        let message = format!("flint-ai-session\n{uuid}\n{nonce}\n{issued_at}");
        let signature = SigningKey::<Sha256>::new(self.key.clone()).sign(message.as_bytes());
        json!({
            "uuid": uuid,
            "publicKey": self.public_key,
            "expiresAt": self.expires_at,
            "keySignature": self.key_signature,
            "nonce": nonce,
            "issuedAt": issued_at,
            "signature": STANDARD.encode(signature.to_bytes()),
        })
    }
}

/// The account's certificate: reused for the run until Minecraft services say to refresh it.
pub async fn certificate(state: &AppState, uuid: &str, access_token: &str) -> CmdResult<Certificate> {
    if let Some((owner, cert)) = cache().lock().unwrap().as_ref() {
        if owner == uuid && now_millis() < cert.refresh_after.min(cert.expires_at - 60_000) {
            return Ok(cert.clone());
        }
    }
    let response = state.http.post(CERTIFICATES).bearer_auth(access_token).header("content-length", "0").send().await.map_err(|e| friendly_network(&e))?;
    let body = response_json(response, "Minecraft services didn't issue a player certificate").await?;
    let cert = Certificate::from_response(&body).ok_or("Minecraft services sent a player certificate Flint couldn't read")?;
    *cache().lock().unwrap() = Some((uuid.to_owned(), cert.clone()));
    Ok(cert)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::pkcs1v15::{Signature, VerifyingKey};
    use rsa::pkcs8::{DecodePublicKey, EncodePrivateKey, EncodePublicKey, LineEnding};
    use rsa::signature::Verifier;
    use rsa::RsaPublicKey;

    #[test]
    fn dates_are_read_like_java_instants() {
        assert_eq!(iso_millis("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(iso_millis("2026-10-15T04:53:17.926513Z"), Some(1_792_039_997_926));
        assert_eq!(iso_millis("2026-10-15T06:53:17.9+02:00"), Some(1_792_039_997_900));
        assert_eq!(iso_millis("2026-10-15T04:53:17Z"), Some(1_792_039_997_000));
        assert_eq!(iso_millis("2026-13-01T00:00:00Z"), None);
        assert_eq!(iso_millis("soon"), None);
        assert_eq!(http_date_millis("Thu, 15 Oct 2026 04:53:17 GMT"), Some(1_792_039_997_000));
    }

    #[test]
    fn certificates_are_read_and_proofs_verify() {
        let key = RsaPrivateKey::new(&mut rand::thread_rng(), 1024).unwrap();
        // Minecraft services label PKCS#8 and X.509 bodies "RSA PRIVATE KEY" / "RSA PUBLIC KEY".
        let private = key.to_pkcs8_pem(LineEnding::LF).unwrap().replace("PRIVATE KEY", "RSA PRIVATE KEY");
        let public = RsaPublicKey::from(&key).to_public_key_pem(LineEnding::LF).unwrap().replace("PUBLIC KEY", "RSA PUBLIC KEY");
        let cert = Certificate::from_response(&json!({
            "keyPair": { "privateKey": private, "publicKey": public },
            "publicKeySignatureV2": "AAAA",
            "expiresAt": "2026-10-15T04:53:17.926513Z",
            "refreshedAfter": "2026-10-15T00:53:17.926513Z",
        }))
        .expect("certificate");
        let proof = cert.proof("0123456789abcdef0123456789abcdef", "nonce-0123456789abcdef", 1_791_954_000_000);
        assert_eq!(proof["expiresAt"], 1_792_039_997_926i64);
        assert_eq!(proof["keySignature"], "AAAA");
        let spki = STANDARD.decode(proof["publicKey"].as_str().unwrap()).unwrap();
        let verifier = VerifyingKey::<Sha256>::new(RsaPublicKey::from_public_key_der(&spki).unwrap());
        let signature = Signature::try_from(STANDARD.decode(proof["signature"].as_str().unwrap()).unwrap().as_slice()).unwrap();
        let message = "flint-ai-session\n0123456789abcdef0123456789abcdef\nnonce-0123456789abcdef\n1791954000000";
        assert!(verifier.verify(message.as_bytes(), &signature).is_ok());
        assert!(verifier.verify(b"something else", &signature).is_err());
    }

    #[test]
    fn the_signature_scheme_matches_openssl_and_the_gateway() {
        // The same test-only public key and openssl SHA-256 signature the gateway's tests verify.
        let spki = STANDARD.decode(include_str!("../tests/fixtures/proof-spki.b64").trim()).unwrap();
        let signature = STANDARD.decode(include_str!("../tests/fixtures/proof-signature.b64").trim()).unwrap();
        let verifier = VerifyingKey::<Sha256>::new(RsaPublicKey::from_public_key_der(&spki).unwrap());
        let message = "flint-ai-session\n0123456789abcdef0123456789abcdef\nnonce-test-0123456789\n1791954000000";
        assert!(verifier.verify(message.as_bytes(), &Signature::try_from(signature.as_slice()).unwrap()).is_ok());
    }

    #[test]
    fn unreadable_certificates_are_rejected() {
        assert!(Certificate::from_response(&json!({})).is_none());
        assert!(Certificate::from_response(&json!({ "keyPair": { "privateKey": "-----BEGIN TEST KEY-----\nnot base64!\n-----END TEST KEY-----", "publicKey": "x" } })).is_none());
    }
}
