//! Measures a Minecraft server's latency with the standard server-list ping: TCP connect time,
//! then a status request and a ping/pong round trip over the Minecraft protocol. This only
//! measures; it doesn't change how the game connects. It's the groundwork for showing real
//! latency and for later diagnosing avoidable local delays.

use crate::core::CmdResult;
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

const TIMEOUT: Duration = Duration::from_secs(5);
const MAX_PACKET: usize = 64 * 1024;
const DEFAULT_PORT: u16 = 25565;

fn write_varint(out: &mut Vec<u8>, value: i32) {
    let mut v = value as u32;
    loop {
        if v & !0x7f == 0 {
            out.push(v as u8);
            return;
        }
        out.push((v as u8 & 0x7f) | 0x80);
        v >>= 7;
    }
}

/// Reads a VarInt from the start of `bytes`, returning it and how many bytes it used.
fn read_varint(bytes: &[u8]) -> Option<(i32, usize)> {
    let mut value: u32 = 0;
    for (i, b) in bytes.iter().enumerate().take(5) {
        value |= ((b & 0x7f) as u32) << (7 * i);
        if b & 0x80 == 0 {
            return Some((value as i32, i + 1));
        }
    }
    None
}

fn packet(id: i32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    write_varint(&mut inner, id);
    inner.extend_from_slice(body);
    let mut out = Vec::new();
    write_varint(&mut out, inner.len() as i32);
    out.extend(inner);
    out
}

fn handshake(host: &str, port: u16) -> Vec<u8> {
    let mut body = Vec::new();
    write_varint(&mut body, -1); // protocol version: "any" for a status query
    write_varint(&mut body, host.len() as i32);
    body.extend_from_slice(host.as_bytes());
    body.extend_from_slice(&port.to_be_bytes());
    write_varint(&mut body, 1); // next state: status
    packet(0x00, &body)
}

/// Splits `host`, `host:port` or `[v6]:port` and rejects anything that isn't a plain address.
pub fn parse_address(address: &str) -> CmdResult<(String, u16)> {
    let address = address.trim();
    let (host, port) = if let Some(rest) = address.strip_prefix('[') {
        let (h, tail) = rest.split_once(']').ok_or("Invalid server address")?;
        (h.to_owned(), tail.strip_prefix(':').map(str::to_owned))
    } else {
        match address.rsplit_once(':') {
            Some((h, p)) if !h.contains(':') => (h.to_owned(), Some(p.to_owned())),
            _ => (address.to_owned(), None),
        }
    };
    let valid = !host.is_empty() && host.len() <= 253 && host.chars().all(|c| c.is_ascii_alphanumeric() || ".-:_".contains(c));
    if !valid {
        return Err("Invalid server address".into());
    }
    let port = match port {
        Some(p) => p.parse::<u16>().ok().filter(|p| *p > 0).ok_or("Invalid server port")?,
        None => DEFAULT_PORT,
    };
    Ok((host, port))
}

async fn read_packet(stream: &mut TcpStream) -> CmdResult<(i32, Vec<u8>)> {
    let mut header = Vec::with_capacity(5);
    let len = loop {
        let mut b = [0u8; 1];
        stream.read_exact(&mut b).await.map_err(|e| e.to_string())?;
        header.push(b[0]);
        if let Some((len, _)) = read_varint(&header) {
            break len;
        }
        if header.len() >= 5 {
            return Err("Malformed reply".into());
        }
    };
    let len = usize::try_from(len).ok().filter(|l| *l > 0 && *l <= MAX_PACKET).ok_or("Reply too large")?;
    let mut data = vec![0u8; len];
    stream.read_exact(&mut data).await.map_err(|e| e.to_string())?;
    let (id, used) = read_varint(&data).ok_or("Malformed reply")?;
    Ok((id, data[used..].to_vec()))
}

async fn probe(host: &str, port: u16) -> CmdResult<Value> {
    let started = Instant::now();
    let mut stream = TcpStream::connect((host, port)).await.map_err(|e| format!("Couldn't connect: {e}"))?;
    let connect_ms = started.elapsed().as_secs_f64() * 1000.0;
    let _ = stream.set_nodelay(true);
    stream.write_all(&handshake(host, port)).await.map_err(|e| e.to_string())?;
    stream.write_all(&packet(0x00, &[])).await.map_err(|e| e.to_string())?;
    let (id, body) = read_packet(&mut stream).await?;
    if id != 0x00 {
        return Err("Unexpected reply".into());
    }
    let (len, used) = read_varint(&body).ok_or("Malformed status")?;
    let text = body.get(used..used + usize::try_from(len).map_err(|_| "Malformed status")?).ok_or("Malformed status")?;
    let status: Value = serde_json::from_slice(text).map_err(|_| "The server sent an unreadable status".to_string())?;

    let payload: i64 = rand::random();
    let sent = Instant::now();
    stream.write_all(&packet(0x01, &payload.to_be_bytes())).await.map_err(|e| e.to_string())?;
    let (id, body) = read_packet(&mut stream).await?;
    let ping_ms = sent.elapsed().as_secs_f64() * 1000.0;
    if id != 0x01 || body.get(..8) != Some(&payload.to_be_bytes()[..]) {
        return Err("The server's ping reply didn't match".into());
    }
    Ok(json!({
        "host": host,
        "port": port,
        "connectMs": (connect_ms * 10.0).round() / 10.0,
        "pingMs": (ping_ms * 10.0).round() / 10.0,
        "version": status["version"]["name"],
        "protocol": status["version"]["protocol"],
        "playersOnline": status["players"]["online"],
        "playersMax": status["players"]["max"],
    }))
}

/// Measures latency to a Minecraft server. SRV records aren't resolved yet, so servers that rely
/// on one need their real host and port.
#[tauri::command]
pub async fn server_ping(address: String) -> CmdResult<Value> {
    let (host, port) = parse_address(&address)?;
    tokio::time::timeout(TIMEOUT, probe(&host, port)).await.map_err(|_| "The server didn't answer in time.".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[test]
    fn varints_round_trip() {
        for v in [0, 1, 127, 128, 255, 25565, 2_097_151, i32::MAX, -1] {
            let mut buf = Vec::new();
            write_varint(&mut buf, v);
            assert_eq!(read_varint(&buf), Some((v, buf.len())), "{v}");
        }
        assert_eq!(read_varint(&[0x80, 0x80]), None, "incomplete");
    }

    #[test]
    fn addresses_are_parsed_strictly() {
        assert_eq!(parse_address("play.example.net").unwrap(), ("play.example.net".into(), 25565));
        assert_eq!(parse_address("127.0.0.1:25570").unwrap(), ("127.0.0.1".into(), 25570));
        assert_eq!(parse_address("[::1]:25570").unwrap(), ("::1".into(), 25570));
        for bad in ["", "host:0", "host:99999", "a b", "http://x", "host/path"] {
            assert!(parse_address(bad).is_err(), "{bad:?}");
        }
    }

    /// A tiny server that answers the status request and echoes the ping.
    async fn fake_server() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            let (id, _) = read_packet(&mut s).await.unwrap();
            assert_eq!(id, 0x00, "handshake");
            let (id, _) = read_packet(&mut s).await.unwrap();
            assert_eq!(id, 0x00, "status request");
            let status = br#"{"version":{"name":"26.3","protocol":800},"players":{"online":3,"max":20}}"#;
            let mut body = Vec::new();
            write_varint(&mut body, status.len() as i32);
            body.extend_from_slice(status);
            s.write_all(&packet(0x00, &body)).await.unwrap();
            let (id, payload) = read_packet(&mut s).await.unwrap();
            assert_eq!(id, 0x01, "ping");
            s.write_all(&packet(0x01, &payload)).await.unwrap();
        });
        port
    }

    #[tokio::test]
    async fn measures_a_server_over_the_status_protocol() {
        let port = fake_server().await;
        let result = server_ping(format!("127.0.0.1:{port}")).await.unwrap();
        assert_eq!(result["version"], "26.3");
        assert_eq!(result["playersOnline"], 3);
        assert!(result["pingMs"].as_f64().unwrap() >= 0.0);
        assert!(result["connectMs"].as_f64().unwrap() >= 0.0);
    }

    #[tokio::test]
    async fn an_unreachable_server_fails_cleanly() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        assert!(server_ping(format!("127.0.0.1:{port}")).await.is_err());
    }
}
