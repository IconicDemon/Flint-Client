use super::*;
use base64::Engine;
use serde_json::json;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// A PNG of `w` × `h`, every pixel `rgba` (the arm columns a slim skin leaves empty cleared when `slim`).
fn skin_png(w: u32, h: u32, slim: bool) -> Vec<u8> {
    let mut px = vec![200u8; (w * h * 4) as usize];
    if slim {
        for y in 20..32 {
            for x in 54..56 {
                px[((y * w + x) * 4 + 3) as usize] = 0;
            }
        }
    }
    let mut out = Vec::new();
    let mut encoder = png::Encoder::new(&mut out, w, h);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.write_header().unwrap().write_image_data(&px).unwrap();
    out
}

/// Mojang's three services on this computer: the profile lookup, the session server and the textures.
struct FakeMojang {
    base: String,
    paths: Arc<Mutex<Vec<String>>>,
}

async fn fake_mojang(texture: Vec<u8>, slim: bool) -> FakeMojang {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let paths: Arc<Mutex<Vec<String>>> = Arc::default();
    let (seen, own) = (paths.clone(), base.clone());
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            let (seen, own, texture) = (seen.clone(), own.clone(), texture.clone());
            tokio::spawn(async move {
                let mut buffer = Vec::new();
                let mut chunk = [0u8; 4096];
                while !buffer.windows(4).any(|w| w == b"\r\n\r\n") {
                    match stream.read(&mut chunk).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => buffer.extend_from_slice(&chunk[..n]),
                    }
                }
                let head = String::from_utf8_lossy(&buffer).to_string();
                let path = head.split(' ').nth(1).unwrap_or_default().to_owned();
                seen.lock().unwrap().push(path.clone());
                let id = "069a79f444e94726a5befca90e38aaf5";
                let (status, body): (u16, Vec<u8>) = if path == "/users/profiles/minecraft/Notch" || path == "/users/profiles/minecraft/notch" {
                    (200, json!({ "id": id, "name": "Notch" }).to_string().into_bytes())
                } else if path.starts_with("/users/profiles/minecraft/") {
                    (404, br#"{"errorMessage":"Couldn't find any profile with name"}"#.to_vec())
                } else if path == format!("/session/minecraft/profile/{id}") {
                    let mut skin = json!({ "url": format!("{own}/texture/abc") });
                    if slim {
                        skin["metadata"] = json!({ "model": "slim" });
                    }
                    let textures = json!({ "textures": { "SKIN": skin } }).to_string();
                    let value = base64::engine::general_purpose::STANDARD.encode(textures);
                    (200, json!({ "id": id, "name": "Notch", "properties": [{ "name": "textures", "value": value }] }).to_string().into_bytes())
                } else if path == "/texture/abc" || path == "/skins/mine.png" {
                    (200, texture)
                } else {
                    (404, Vec::new())
                };
                let out = format!("HTTP/1.1 {status} X\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                let _ = stream.write_all(out.as_bytes()).await;
                let _ = stream.write_all(&body).await;
                let _ = stream.shutdown().await;
            });
        }
    });
    FakeMojang { base, paths }
}

fn local(m: &FakeMojang) -> Mojang {
    Mojang { api: m.base.clone(), session: m.base.clone(), loopback_textures: true }
}

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("flint-skin-library-{name}-{}", crate::core::random_hex(6)));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

#[tokio::test]
async fn a_username_adds_its_current_skin_to_the_list_with_mojang_s_arm_model() {
    let mojang = fake_mojang(skin_png(64, 64, false), true).await;
    let dir = temp_dir("username");
    let http = crate::core::test_client();
    let skin = add_in(&http, &local(&mojang), &dir, Source::Username("notch".into())).await.unwrap();
    assert_eq!((skin.name.as_str(), skin.source.as_str(), skin.variant.as_str(), skin.legacy), ("Notch", "username", "slim", false));
    assert_eq!(skin.id.len(), 64);
    assert_eq!(list_in(&dir), vec![skin.clone()]);
    assert_eq!(png_in(&dir, &skin.id).unwrap(), skin_png(64, 64, false));
    // The same picture again is one entry.
    add_in(&http, &local(&mojang), &dir, Source::Username("Notch".into())).await.unwrap();
    assert_eq!(list_in(&dir).len(), 1);
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn a_username_that_doesn_t_exist_is_refused_in_words_and_nothing_is_kept() {
    let mojang = fake_mojang(skin_png(64, 64, false), false).await;
    let dir = temp_dir("missing");
    let http = crate::core::test_client();
    let error = add_in(&http, &local(&mojang), &dir, Source::Username("nobody_here".into())).await.unwrap_err();
    assert_eq!(error, "No Minecraft player is called nobody_here.");
    let error = add_in(&http, &local(&mojang), &dir, Source::Username("bad name!".into())).await.unwrap_err();
    assert!(error.contains("letters, digits"), "{error}");
    assert!(list_in(&dir).is_empty());
    assert!(!mojang.paths.lock().unwrap().iter().any(|p| p.contains("bad")), "an invalid name is never sent");
}

#[tokio::test]
async fn an_address_must_be_a_64_by_64_or_64_by_32_png() {
    let http = crate::core::test_client();
    for (texture, ok) in [(skin_png(64, 64, true), Some(("slim", false))), (skin_png(64, 32, false), Some(("classic", true))), (skin_png(128, 128, false), None), (b"<html>".to_vec(), None)] {
        let mojang = fake_mojang(texture, false).await;
        let dir = temp_dir("url");
        let got = add_in(&http, &local(&mojang), &dir, Source::Url(format!("{}/skins/mine.png", mojang.base))).await;
        match ok {
            Some((variant, legacy)) => {
                let skin = got.unwrap();
                assert_eq!((skin.name.as_str(), skin.variant.as_str(), skin.legacy, skin.source.as_str()), ("mine", variant, legacy, "url"));
            }
            None => {
                let error = got.unwrap_err();
                assert!(error.contains("64×64"), "{error}");
                assert!(list_in(&dir).is_empty());
            }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[test]
fn only_public_https_addresses_are_downloaded() {
    assert!(skin_url("https://example.com/skin.png", false).is_ok());
    for bad in ["http://example.com/s.png", "https://localhost/s.png", "https://192.168.1.2/s.png", "https://10.0.0.1/s.png", "https://127.0.0.1/s.png",
        "https://user:pw@example.com/s.png", "file:///C:/s.png", "not a url", "https://printer.local/s.png"] {
        assert!(skin_url(bad, false).is_err(), "{bad}");
    }
    assert!(skin_url("http://127.0.0.1:5000/s.png", true).is_ok(), "loopback only in tests and isolated runs");
}

#[test]
fn removing_takes_the_picture_too_and_the_list_keeps_its_newest_skins() {
    let dir = temp_dir("remove");
    let base = SavedSkin { id: String::new(), name: "a".into(), source: "url".into(), origin: "x".into(), variant: "classic".into(), legacy: false, added_at: 1 };
    let png = skin_png(64, 64, false);
    let first = keep_in(&dir, &png, base.clone()).unwrap();
    let png = skin_png(64, 64, true);
    let second = keep_in(&dir, &png, base.clone()).unwrap();
    assert_eq!(list_in(&dir).iter().map(|s| s.id.clone()).collect::<Vec<_>>(), vec![second.id.clone(), first.id.clone()], "newest first");
    let left = remove_in(&dir, &first.id).unwrap();
    assert_eq!(left.len(), 1);
    assert!(!dir.join(format!("{}.png", first.id)).exists());
    assert!(remove_in(&dir, "../library").is_err());
    assert!(png_in(&dir, &first.id).is_err());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_session_profile_names_its_skin_and_arm_model() {
    let textures = json!({ "textures": { "SKIN": { "url": "http://textures.minecraft.net/texture/1", "metadata": { "model": "slim" } } } }).to_string();
    let profile = json!({ "properties": [{ "name": "textures", "value": base64::engine::general_purpose::STANDARD.encode(textures) }] });
    assert_eq!(texture_of(&profile), Some(("http://textures.minecraft.net/texture/1".into(), "slim")));
    assert_eq!(texture_of(&json!({ "properties": [] })), None);
}
