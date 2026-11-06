//! What's playing on this computer, for the game's Music Player: the media session Windows shows in its own media controls
//! (the volume flyout), whichever app it belongs to: Spotify, Apple Music, a browser, Media Player... Flint never connects to
//! a music service: it reads what Windows already shows about the session (title, artist, album art, position) and presses
//! the same Previous / Play-Pause / Next buttons Windows' controls press. The game asks over the game link (`gamelink.rs`:
//! `media`, `mediaArt`, `mediaControl`).
//!
//! - Windows only (the System Media Transport Controls); elsewhere every answer says Music Player needs Windows.
//! - The session shown: the one playing (Windows' current session first, then a music app), else Windows' current one.
//!   "Music apps only" leaves browsers and video players out.
//! - The position is as of now: the app reports where it was at its last update, and playing time since then is added.
//! - Album art is decoded once per change (Windows' imaging, cover-cropped to [`ART_SOURCE`] px square) and kept in memory;
//!   each change gets a new version, and the game asks for it at the size it draws it, scaled down here by area averaging.
//!   A new track's art is checked again a few times while the app catches up.
// Elsewhere only the "needs Windows" answers are used; the rules below are still built and tested everywhere.
#![cfg_attr(not(windows), allow(dead_code))]
use serde::{Deserialize, Serialize};

/// The square album art is kept at, in pixels (the game asks for it at its drawn size, at most this).
pub const ART_SOURCE: u32 = 256;
/// The smallest size the game may ask for.
pub const ART_MIN: u32 = 8;
/// After a track change, its art is read again on these polls (counted from the change; a poll is about a second apart):
/// apps often send the title first and the picture a moment later.
const ART_CHECKS: [u32; 5] = [0, 1, 2, 4, 8];

/// What's playing, as the game shows it. `supported` false: this computer can't tell (`reason` says why).
#[derive(Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct MediaReply {
    pub supported: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    /// A session with something in it (a title).
    pub active: bool,
    pub playing: bool,
    pub title: String,
    pub artist: String,
    pub album: String,
    /// The app playing it, as people know it ("Spotify", "Apple Music", "Chrome").
    pub app: String,
    /// Where it is now, in milliseconds (0 when the app doesn't say).
    pub position_ms: u64,
    /// How long it is, in milliseconds (0 when the app doesn't say: a stream, or an app without a timeline).
    pub duration_ms: u64,
    /// The album art's version (0: none); `mediaArt` hands it over.
    pub art_version: u32,
    pub can_previous: bool,
    pub can_next: bool,
    pub can_toggle: bool,
    pub can_seek: bool,
}

/// The album art: `width × height` pixels, row by row, 4 bytes each (red, green, blue, alpha), in base64.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ArtReply {
    pub version: u32,
    pub width: u32,
    pub height: u32,
    pub rgba: String,
}

/// A button of the player.
#[derive(Deserialize, Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum MediaAction {
    Toggle,
    Next,
    Previous,
    /// To a position (`positionMs`).
    Seek,
}

/// Why Music Player can't work on this computer (every system but Windows).
#[cfg_attr(windows, allow(dead_code))]
pub const NEEDS_WINDOWS: &str = "Music Player needs Windows for now";

/// The answer for a computer that can't tell what's playing.
#[cfg_attr(windows, allow(dead_code))]
pub fn unsupported(reason: &str) -> MediaReply {
    MediaReply { supported: false, reason: Some(reason.to_owned()), ..Default::default() }
}

// ---------- the pure rules (every platform; unit-tested) ----------

/// The app behind a media session, as people know it, from Windows' id for it (an executable's name, or a Store app's
/// `Package_publisher!App`).
pub fn app_name(app_id: &str) -> String {
    let lower = app_id.to_ascii_lowercase();
    const KNOWN: &[(&str, &str)] = &[
        ("spotify", "Spotify"),
        ("applemusic", "Apple Music"),
        ("apple music", "Apple Music"),
        ("itunes", "iTunes"),
        ("zunemusic", "Media Player"),
        ("tidal", "TIDAL"),
        ("deezer", "Deezer"),
        ("amazonmusic", "Amazon Music"),
        ("amazon music", "Amazon Music"),
        ("youtubemusic", "YouTube Music"),
        ("youtube music", "YouTube Music"),
        ("soundcloud", "SoundCloud"),
        ("qobuz", "Qobuz"),
        ("pandora", "Pandora"),
        ("foobar2000", "foobar2000"),
        ("musicbee", "MusicBee"),
        ("aimp", "AIMP"),
        ("winamp", "Winamp"),
        ("vlc", "VLC"),
        ("msedge", "Edge"),
        ("microsoftedge", "Edge"),
        ("chrome", "Chrome"),
        ("firefox", "Firefox"),
        ("opera", "Opera"),
        ("brave", "Brave"),
        ("vivaldi", "Vivaldi"),
        ("thebrowsercompany.arc", "Arc"),
        ("discord", "Discord"),
        ("plex", "Plex"),
        ("netflix", "Netflix"),
    ];
    for &(needle, name) in KNOWN {
        if lower.contains(needle) {
            return name.to_owned();
        }
    }
    // Anything else: the executable's or the package's own name, without its extension or publisher.
    let mut name = app_id.rsplit(['\\', '/']).next().unwrap_or(app_id);
    name = name.split('!').next().unwrap_or(name);
    if let Some(stem) = name.strip_suffix(".exe").or_else(|| name.strip_suffix(".EXE")) {
        name = stem;
    }
    if let Some((package, _publisher)) = name.split_once('_') {
        name = package;
    }
    name = name.rsplit('.').next().unwrap_or(name);
    let mut chars = name.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

/// Whether the session's app is a music player (for "Music apps only"): not a browser, a video app or a call.
pub fn is_music_app(app_id: &str) -> bool {
    matches!(
        app_name(app_id).as_str(),
        "Spotify"
            | "Apple Music"
            | "iTunes"
            | "Media Player"
            | "TIDAL"
            | "Deezer"
            | "Amazon Music"
            | "YouTube Music"
            | "SoundCloud"
            | "Qobuz"
            | "Pandora"
            | "foobar2000"
            | "MusicBee"
            | "AIMP"
            | "Winamp"
    )
}

/// One session as the chooser sees it.
#[derive(Clone, Debug, PartialEq)]
pub struct Candidate {
    pub app_id: String,
    pub playing: bool,
    /// Windows' current session (the one its media controls show).
    pub current: bool,
}

/// The session to show: Windows' current one if it plays, else a playing one (a music app first), else Windows' current
/// one, else the first music app's. `music_only` leaves the rest out entirely.
pub fn choose(sessions: &[Candidate], music_only: bool) -> Option<usize> {
    let allowed = |c: &Candidate| !music_only || is_music_app(&c.app_id);
    let find = |test: &dyn Fn(&Candidate) -> bool| sessions.iter().position(|c| allowed(c) && test(c));
    find(&|c| c.current && c.playing)
        .or_else(|| find(&|c| c.playing && is_music_app(&c.app_id)))
        .or_else(|| find(&|c| c.playing))
        .or_else(|| find(&|c| c.current))
        .or_else(|| find(&|c| is_music_app(&c.app_id)))
}

/// Windows' times are in 100-nanosecond ticks; its clock counts them from 1601.
const TICKS_PER_MS: i64 = 10_000;
const UNIX_EPOCH_TICKS: i64 = 116_444_736_000_000_000;

/// Now on Windows' clock (100 ns ticks since 1601).
pub fn now_ticks() -> i64 {
    let since = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    UNIX_EPOCH_TICKS + since.as_secs() as i64 * 10_000_000 + since.subsec_nanos() as i64 / 100
}

/// Where a session is now and how long it is, in milliseconds: its reported position (as of `updated`, a time on Windows'
/// clock) plus the playing time since, within the track. An update time that is missing or in the future adds nothing.
pub fn position_now(position: i64, start: i64, end: i64, updated: i64, now: i64, playing: bool) -> (u64, u64) {
    let duration = (end - start).max(0);
    let mut at = (position - start).max(0);
    if playing && updated > 0 && updated <= now + 5_000 * TICKS_PER_MS {
        at += (now - updated).max(0);
    }
    if duration > 0 {
        at = at.min(duration);
    }
    ((at / TICKS_PER_MS) as u64, (duration / TICKS_PER_MS) as u64)
}

/// `src` (a `side × side` RGBA square) at `size × size`, each new pixel the average of the source area it covers (so a
/// smaller picture stays smooth, never aliased). Colours are averaged by their alpha.
pub fn scale_square(src: &[u8], side: u32, size: u32) -> Vec<u8> {
    let (side, size) = (side as usize, size.max(1) as usize);
    if size == side {
        return src.to_vec();
    }
    // Each output column's (and row's) span of source pixels, with the coverage of each.
    let spans = |n: usize| -> Vec<Vec<(usize, f32)>> {
        let k = side as f32 / n as f32;
        (0..n)
            .map(|i| {
                let (a, b) = (i as f32 * k, (i + 1) as f32 * k);
                let mut cover = Vec::new();
                let mut s = a.floor() as usize;
                while (s as f32) < b && s < side {
                    let w = (b.min(s as f32 + 1.0) - a.max(s as f32)).max(0.0);
                    if w > 0.0 {
                        cover.push((s, w));
                    }
                    s += 1;
                }
                cover
            })
            .collect()
    };
    let cols = spans(size);
    let rows = spans(size);
    let mut out = vec![0u8; size * size * 4];
    for (oy, row) in rows.iter().enumerate() {
        for (ox, col) in cols.iter().enumerate() {
            let mut sum = [0f32; 4];
            let mut area = 0f32;
            for &(sy, wy) in row {
                for &(sx, wx) in col {
                    let w = wx * wy;
                    let p = (sy * side + sx) * 4;
                    let alpha = src[p + 3] as f32;
                    sum[0] += src[p] as f32 * alpha * w;
                    sum[1] += src[p + 1] as f32 * alpha * w;
                    sum[2] += src[p + 2] as f32 * alpha * w;
                    sum[3] += alpha * w;
                    area += w;
                }
            }
            let o = (oy * size + ox) * 4;
            if sum[3] > 0.0 {
                out[o] = (sum[0] / sum[3]).round().clamp(0.0, 255.0) as u8;
                out[o + 1] = (sum[1] / sum[3]).round().clamp(0.0, 255.0) as u8;
                out[o + 2] = (sum[2] / sum[3]).round().clamp(0.0, 255.0) as u8;
            }
            out[o + 3] = (sum[3] / area.max(f32::EPSILON)).round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

/// A cheap fingerprint of decoded pixels (FNV-1a), to tell a new picture from the one already kept.
pub fn fingerprint(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for &b in bytes {
        hash ^= b as u64;
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    hash
}

/// The art kept for the track shown: its version and its `ART_SOURCE`-square pixels.
pub struct ArtCache {
    /// app, title, artist, album of the track the art is for.
    track: String,
    polls_since_change: u32,
    fingerprint: u64,
    version: u32,
    pixels: Option<std::sync::Arc<Vec<u8>>>,
    /// The last version handed out, so every change gets a new one (never 0).
    counter: u32,
}

impl Default for ArtCache {
    fn default() -> Self {
        Self::new()
    }
}

impl ArtCache {
    pub const fn new() -> Self {
        Self { track: String::new(), polls_since_change: 0, fingerprint: 0, version: 0, pixels: None, counter: 0 }
    }

    /// A poll of `track`: whether its art should be read now (a new track, or one of the checks after it changed). A new track
    /// drops the art of the one before.
    pub fn poll(&mut self, track: &str) -> bool {
        if self.track != track {
            self.track = track.to_owned();
            self.polls_since_change = 0;
            self.fingerprint = 0;
            self.version = 0;
            self.pixels = None;
        } else {
            self.polls_since_change = self.polls_since_change.saturating_add(1);
        }
        ART_CHECKS.contains(&self.polls_since_change)
    }

    /// The art just read for the track (None: the app shows none); a different picture gets a new version.
    pub fn read(&mut self, pixels: Option<Vec<u8>>) {
        match pixels {
            Some(pixels) => {
                let print = fingerprint(&pixels);
                if self.pixels.is_none() || print != self.fingerprint {
                    self.counter = self.counter.wrapping_add(1).max(1);
                    self.version = self.counter;
                    self.fingerprint = print;
                    self.pixels = Some(std::sync::Arc::new(pixels));
                }
            }
            None => {
                // Keep a picture already shown: an app that drops its art for a moment shouldn't make it flicker.
            }
        }
    }

    pub fn version(&self) -> u32 {
        if self.pixels.is_some() {
            self.version
        } else {
            0
        }
    }

    /// The art at `size × size` (clamped to [`ART_MIN`]..=[`ART_SOURCE`]), or None when there is none.
    pub fn art(&self, size: u32) -> Option<ArtReply> {
        use base64::Engine;
        let pixels = self.pixels.as_ref()?;
        let size = size.clamp(ART_MIN, ART_SOURCE);
        let scaled = scale_square(pixels, ART_SOURCE, size);
        Some(ArtReply { version: self.version, width: size, height: size, rgba: base64::engine::general_purpose::STANDARD.encode(scaled) })
    }
}

/// The most characters of a title, artist, album or app name sent to the game (a HUD line shows far fewer; an app that
/// sets a huge title must not overflow the game's answer).
pub const MAX_TEXT: usize = 256;

/// `text` trimmed and cut to [`MAX_TEXT`] characters.
pub fn clip(text: &str) -> String {
    let text = text.trim();
    match text.char_indices().nth(MAX_TEXT) {
        Some((end, _)) => text[..end].trim_end().to_owned(),
        None => text.to_owned(),
    }
}

/// The text identifying a track, for telling a new one from the one shown.
pub fn track_key(app: &str, title: &str, artist: &str, album: &str) -> String {
    format!("{app}\u{1}{title}\u{1}{artist}\u{1}{album}")
}

// ---------- this computer ----------

/// What's playing now (blocking: run it off the async runtime). `music_only`: music apps only. Err: Windows didn't answer
/// this time (busy, or its media service restarting): the game keeps what it shows and asks again.
pub fn read(music_only: bool) -> Result<MediaReply, String> {
    #[cfg(windows)]
    {
        windows_media::read(music_only)
    }
    #[cfg(not(windows))]
    {
        let _ = music_only;
        Ok(unsupported(NEEDS_WINDOWS))
    }
}

/// The album art of the track shown, at `size` px square (blocking). Err when there is none.
pub fn art(size: u32) -> Result<ArtReply, String> {
    #[cfg(windows)]
    {
        windows_media::art(size)
    }
    #[cfg(not(windows))]
    {
        let _ = size;
        Err(NEEDS_WINDOWS.into())
    }
}

/// Presses a button of the session shown (blocking). `music_only` picks the session as [`read`] does.
pub fn control(action: MediaAction, position_ms: Option<u64>, music_only: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        windows_media::control(action, position_ms, music_only)
    }
    #[cfg(not(windows))]
    {
        let _ = (action, position_ms, music_only);
        Err(NEEDS_WINDOWS.into())
    }
}

#[cfg(windows)]
mod windows_media {
    use super::*;
    use std::sync::{Mutex, MutexGuard, TryLockError};
    use windows::Graphics::Imaging::{
        BitmapAlphaMode, BitmapBounds, BitmapDecoder, BitmapInterpolationMode, BitmapPixelFormat, BitmapTransform, ColorManagementMode,
        ExifOrientationMode,
    };
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session, GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };
    use windows::Storage::Streams::IRandomAccessStreamReference;

    /// Windows' session manager (asked for once), and the art of the track shown.
    struct Watcher {
        manager: Option<Manager>,
        art: ArtCache,
    }

    static WATCHER: Mutex<Watcher> = Mutex::new(Watcher { manager: None, art: ArtCache::new() });

    /// "Busy" after half a second rather than waiting on: a call to Windows that hangs mustn't stack up a thread per poll
    /// behind it (two games asking at once just take turns).
    const BUSY: &str = "busy";

    fn lock() -> Result<MutexGuard<'static, Watcher>, String> {
        for _ in 0..25 {
            match WATCHER.try_lock() {
                Ok(guard) => return Ok(guard),
                Err(TryLockError::Poisoned(poisoned)) => return Ok(poisoned.into_inner()),
                Err(TryLockError::WouldBlock) => std::thread::sleep(std::time::Duration::from_millis(20)),
            }
        }
        Err(BUSY.into())
    }

    fn failed(e: &windows::core::Error) -> String {
        format!("Windows didn't answer ({:#010x})", e.code().0)
    }

    fn manager(watcher: &mut Watcher) -> windows::core::Result<Manager> {
        if let Some(manager) = &watcher.manager {
            return Ok(manager.clone());
        }
        let manager = Manager::RequestAsync()?.join()?;
        watcher.manager = Some(manager.clone());
        Ok(manager)
    }

    /// The session [`choose`] picks.
    fn session(manager: &Manager, music_only: bool) -> windows::core::Result<Option<Session>> {
        let current = manager.GetCurrentSession().ok();
        let current_id = current.as_ref().and_then(|s| s.SourceAppUserModelId().ok()).map(|id| id.to_string());
        let list = manager.GetSessions()?;
        let mut sessions = Vec::new();
        let mut candidates = Vec::new();
        for i in 0..list.Size()? {
            let Ok(s) = list.GetAt(i) else { continue };
            let app_id = s.SourceAppUserModelId().map(|id| id.to_string()).unwrap_or_default();
            let playing = s.GetPlaybackInfo().and_then(|info| info.PlaybackStatus()).map(|st| st == Status::Playing).unwrap_or(false);
            let is_current = current_id.as_deref() == Some(app_id.as_str());
            candidates.push(Candidate { app_id, playing, current: is_current });
            sessions.push(s);
        }
        Ok(choose(&candidates, music_only).map(|i| sessions.swap_remove(i)))
    }

    pub fn read(music_only: bool) -> Result<MediaReply, String> {
        let mut watcher = lock()?;
        read_locked(&mut watcher, music_only).map_err(|e| {
            // Windows' media service restarted or refused: ask for its manager again next time.
            watcher.manager = None;
            failed(&e)
        })
    }

    fn read_locked(watcher: &mut Watcher, music_only: bool) -> windows::core::Result<MediaReply> {
        let manager = manager(watcher)?;
        let Some(session) = session(&manager, music_only)? else {
            return Ok(MediaReply { supported: true, ..Default::default() });
        };
        let app_id = session.SourceAppUserModelId().map(|id| id.to_string()).unwrap_or_default();
        let props = session.TryGetMediaPropertiesAsync()?.join()?;
        let title = clip(&props.Title().map(|s| s.to_string()).unwrap_or_default());
        let mut artist = clip(&props.Artist().map(|s| s.to_string()).unwrap_or_default());
        if artist.is_empty() {
            artist = clip(&props.AlbumArtist().map(|s| s.to_string()).unwrap_or_default());
        }
        let album = clip(&props.AlbumTitle().map(|s| s.to_string()).unwrap_or_default());
        let app = clip(&app_name(&app_id));
        let info = session.GetPlaybackInfo()?;
        let playing = info.PlaybackStatus().map(|s| s == Status::Playing).unwrap_or(false);
        let controls = info.Controls()?;
        let timeline = session.GetTimelineProperties()?;
        let (position_ms, duration_ms) = position_now(
            timeline.Position().map(|t| t.Duration).unwrap_or(0),
            timeline.StartTime().map(|t| t.Duration).unwrap_or(0),
            timeline.EndTime().map(|t| t.Duration).unwrap_or(0),
            timeline.LastUpdatedTime().map(|t| t.UniversalTime).unwrap_or(0),
            now_ticks(),
            playing,
        );
        if !title.is_empty() && watcher.art.poll(&track_key(&app, &title, &artist, &album)) {
            let pixels = props.Thumbnail().ok().and_then(|thumbnail| decode(&thumbnail).ok());
            watcher.art.read(pixels);
        }
        Ok(MediaReply {
            supported: true,
            reason: None,
            active: !title.is_empty(),
            playing,
            title,
            artist,
            album,
            app,
            position_ms,
            duration_ms,
            art_version: watcher.art.version(),
            can_previous: controls.IsPreviousEnabled().unwrap_or(false),
            can_next: controls.IsNextEnabled().unwrap_or(false),
            can_toggle: controls.IsPlayPauseToggleEnabled().unwrap_or(false)
                || controls.IsPlayEnabled().unwrap_or(false)
                || controls.IsPauseEnabled().unwrap_or(false),
            can_seek: controls.IsPlaybackPositionEnabled().unwrap_or(false) && duration_ms > 0,
        })
    }

    /// The thumbnail as an `ART_SOURCE` square: scaled to cover it (Windows' Fant filter) and cropped to its middle.
    fn decode(thumbnail: &IRandomAccessStreamReference) -> windows::core::Result<Vec<u8>> {
        let stream = thumbnail.OpenReadAsync()?.join()?;
        let decoder = BitmapDecoder::CreateAsync(&stream)?.join()?;
        let (w, h) = (decoder.PixelWidth()?.max(1), decoder.PixelHeight()?.max(1));
        let k = ART_SOURCE as f64 / w.min(h) as f64;
        let sw = ((w as f64 * k).round() as u32).max(ART_SOURCE);
        let sh = ((h as f64 * k).round() as u32).max(ART_SOURCE);
        let transform = BitmapTransform::new()?;
        transform.SetScaledWidth(sw)?;
        transform.SetScaledHeight(sh)?;
        transform.SetInterpolationMode(BitmapInterpolationMode::Fant)?;
        transform.SetBounds(BitmapBounds { X: (sw - ART_SOURCE) / 2, Y: (sh - ART_SOURCE) / 2, Width: ART_SOURCE, Height: ART_SOURCE })?;
        let data = decoder
            .GetPixelDataTransformedAsync(
                BitmapPixelFormat::Rgba8,
                BitmapAlphaMode::Straight,
                &transform,
                ExifOrientationMode::IgnoreExifOrientation,
                ColorManagementMode::DoNotColorManage,
            )?
            .join()?;
        let pixels = data.DetachPixelData()?.to_vec();
        if pixels.len() != (ART_SOURCE * ART_SOURCE * 4) as usize {
            return Err(windows::core::Error::from_hresult(windows::core::HRESULT(0x8000_4005u32 as i32)));
        }
        Ok(pixels)
    }

    pub fn art(size: u32) -> Result<ArtReply, String> {
        let watcher = lock()?;
        watcher.art.art(size).ok_or_else(|| "No album art".to_owned())
    }

    pub fn control(action: MediaAction, position_ms: Option<u64>, music_only: bool) -> Result<(), String> {
        let mut watcher = lock()?;
        let pressed = (|| -> windows::core::Result<bool> {
            let manager = manager(&mut watcher)?;
            let Some(session) = session(&manager, music_only)? else { return Ok(false) };
            match action {
                MediaAction::Toggle => session.TryTogglePlayPauseAsync()?.join(),
                MediaAction::Next => session.TrySkipNextAsync()?.join(),
                MediaAction::Previous => session.TrySkipPreviousAsync()?.join(),
                MediaAction::Seek => {
                    let start = session.GetTimelineProperties().and_then(|t| t.StartTime()).map(|t| t.Duration).unwrap_or(0);
                    let to = start + position_ms.unwrap_or(0) as i64 * TICKS_PER_MS;
                    session.TryChangePlaybackPositionAsync(to)?.join()
                }
            }
        })();
        match pressed {
            Ok(true) => Ok(()),
            Ok(false) => Err("The app didn't take it".into()),
            Err(e) => {
                watcher.manager = None;
                Err(failed(&e))
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::super::MediaAction;
        use std::time::Duration;
        use windows::Graphics::Imaging::{BitmapAlphaMode, BitmapEncoder, BitmapPixelFormat};
        use windows::Media::Core::MediaSource;
        use windows::Media::MediaPlaybackType;
        use windows::Media::Playback::{MediaPlaybackItem, MediaPlayer};
        use windows::Security::Cryptography::CryptographicBuffer;
        use windows::Storage::Streams::{InMemoryRandomAccessStream, RandomAccessStreamReference};

        fn stream(bytes: &[u8]) -> InMemoryRandomAccessStream {
            let s = InMemoryRandomAccessStream::new().unwrap();
            s.WriteAsync(&CryptographicBuffer::CreateFromByteArray(bytes).unwrap()).unwrap().join().unwrap();
            s.Seek(0).unwrap();
            s
        }

        /// Twenty seconds of silence (8 kHz, mono, 16-bit WAV).
        fn silence() -> Vec<u8> {
            let data = 8000 * 20 * 2u32;
            let mut wav = Vec::new();
            wav.extend_from_slice(b"RIFF");
            wav.extend_from_slice(&(36 + data).to_le_bytes());
            wav.extend_from_slice(b"WAVEfmt ");
            for v in [16u32.to_le_bytes().to_vec(), 1u16.to_le_bytes().to_vec(), 1u16.to_le_bytes().to_vec(), 8000u32.to_le_bytes().to_vec(),
                16000u32.to_le_bytes().to_vec(), 2u16.to_le_bytes().to_vec(), 16u16.to_le_bytes().to_vec()]
            {
                wav.extend_from_slice(&v);
            }
            wav.extend_from_slice(b"data");
            wav.extend_from_slice(&data.to_le_bytes());
            wav.resize(wav.len() + data as usize, 0);
            wav
        }

        /// A song played in this test (muted, silent), as Windows' media controls show it, with a 64 px cover: the left half
        /// red, the right half blue. Read back through Flint's own path: title, artist, playing, the art (decoded, cropped,
        /// scaled), then play/pause. Run by hand on Windows: `cargo test --lib media::windows_media -- --ignored --nocapture`.
        /// It presses play/pause only once it has read its own song as the one shown.
        #[test]
        #[ignore]
        fn reads_and_presses_a_session_made_here() {
            let png = InMemoryRandomAccessStream::new().unwrap();
            let encoder = BitmapEncoder::CreateAsync(BitmapEncoder::PngEncoderId().unwrap(), &png).unwrap().join().unwrap();
            let mut pixels = Vec::new();
            for _y in 0..64 {
                for x in 0..64 {
                    pixels.extend_from_slice(if x < 32 { &[255, 0, 0, 255] } else { &[0, 0, 255, 255] });
                }
            }
            encoder.SetPixelData(BitmapPixelFormat::Rgba8, BitmapAlphaMode::Straight, 64, 64, 96.0, 96.0, &pixels).unwrap();
            encoder.FlushAsync().unwrap().join().unwrap();
            png.Seek(0).unwrap();

            // The player shows its item's display properties in Windows' media controls (it manages them itself).
            let player = MediaPlayer::new().unwrap();
            player.SetIsMuted(true).unwrap();
            let item = MediaPlaybackItem::Create(&MediaSource::CreateFromStream(&stream(&silence()), &"audio/wav".into()).unwrap()).unwrap();
            let shown = item.GetDisplayProperties().unwrap();
            shown.SetType(MediaPlaybackType::Music).unwrap();
            shown.MusicProperties().unwrap().SetTitle(&"Flint Test Song".into()).unwrap();
            shown.MusicProperties().unwrap().SetArtist(&"Flint QA".into()).unwrap();
            shown.SetThumbnail(&RandomAccessStreamReference::CreateFromStream(&png).unwrap()).unwrap();
            item.ApplyDisplayProperties(&shown).unwrap();
            player.SetSource(&item).unwrap();
            player.Play().unwrap();

            let mut seen = None;
            for _ in 0..40 {
                std::thread::sleep(Duration::from_millis(250));
                if let Ok(reply) = super::read(false) {
                    if reply.title == "Flint Test Song" && reply.playing && reply.art_version != 0 {
                        seen = Some(reply);
                        break;
                    }
                }
            }
            let reply = seen.expect("the song played here is the one shown, with its art");
            println!("{reply:?}");
            assert_eq!(reply.artist, "Flint QA");
            assert!(reply.can_toggle);
            let art = super::art(16).expect("art");
            use base64::Engine;
            let rgba = base64::engine::general_purpose::STANDARD.decode(art.rgba).unwrap();
            assert_eq!(rgba.len(), 16 * 16 * 4);
            let at = |x: usize, y: usize| &rgba[(y * 16 + x) * 4..(y * 16 + x) * 4 + 4];
            assert!(at(2, 8)[0] > 200 && at(2, 8)[2] < 60, "the left is red: {:?}", at(2, 8));
            assert!(at(13, 8)[2] > 200 && at(13, 8)[0] < 60, "the right is blue: {:?}", at(13, 8));

            super::control(MediaAction::Toggle, None, false).expect("pause");
            let mut paused = false;
            for _ in 0..20 {
                std::thread::sleep(Duration::from_millis(150));
                if let Ok(reply) = super::read(false) {
                    if reply.title == "Flint Test Song" && !reply.playing {
                        paused = true;
                        break;
                    }
                }
            }
            player.Pause().ok();
            assert!(paused, "play/pause reached the app");
        }

        /// What's playing on this computer right now (run by hand: `cargo test --lib media -- --ignored --nocapture`).
        #[test]
        #[ignore]
        fn reads_this_computers_session() {
            let reply = super::read(false).expect("Windows answers");
            println!("{reply:?}");
            assert!(reply.supported);
            if reply.art_version != 0 {
                let art = super::art(64).expect("art");
                assert_eq!((art.width, art.height), (64, 64));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn apps_are_named_as_people_know_them() {
        assert_eq!(app_name("Spotify.exe"), "Spotify");
        assert_eq!(app_name("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"), "Spotify");
        assert_eq!(app_name("AppleInc.AppleMusicWin_nzyj5cx40ttyp!App"), "Apple Music");
        assert_eq!(app_name("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"), "Media Player");
        assert_eq!(app_name("chrome.exe"), "Chrome");
        assert_eq!(app_name("MSEdge"), "Edge");
        assert_eq!(app_name("308046B0AF4A39CB"), "308046B0AF4A39CB", "an id Flint doesn't know is shown as it is");
        assert_eq!(app_name("C:\\Program Files\\Foo\\cooltunes.exe"), "Cooltunes");
        assert_eq!(app_name("Contoso.Player_abc123!App"), "Player");
        assert_eq!(app_name(""), "");
    }

    #[test]
    fn long_text_is_cut_on_a_character() {
        assert_eq!(clip("  Midnight City  "), "Midnight City");
        let long = "é".repeat(MAX_TEXT + 50);
        assert_eq!(clip(&long).chars().count(), MAX_TEXT, "characters, not bytes");
        assert_eq!(clip(&"a".repeat(MAX_TEXT)), "a".repeat(MAX_TEXT));
    }

    #[test]
    fn music_apps_are_told_from_browsers() {
        assert!(is_music_app("Spotify.exe"));
        assert!(is_music_app("AppleInc.AppleMusicWin_nzyj5cx40ttyp!App"));
        assert!(!is_music_app("chrome.exe"));
        assert!(!is_music_app("firefox.exe"));
        assert!(!is_music_app("vlc.exe"), "VLC plays films too");
    }

    fn c(app_id: &str, playing: bool, current: bool) -> Candidate {
        Candidate { app_id: app_id.into(), playing, current }
    }

    #[test]
    fn the_session_shown_is_the_one_playing() {
        // Windows' current session, when it plays.
        assert_eq!(choose(&[c("Spotify.exe", true, false), c("chrome.exe", true, true)], false), Some(1));
        // A paused current session gives way to one that plays, a music app first.
        assert_eq!(choose(&[c("chrome.exe", false, true), c("firefox.exe", true, false), c("Spotify.exe", true, false)], false), Some(2));
        // Nothing playing: Windows' current one.
        assert_eq!(choose(&[c("Spotify.exe", false, false), c("chrome.exe", false, true)], false), Some(1));
        // Music apps only: the browser is never shown.
        assert_eq!(choose(&[c("Spotify.exe", false, false), c("chrome.exe", true, true)], true), Some(0));
        assert_eq!(choose(&[c("chrome.exe", true, true)], true), None);
        assert_eq!(choose(&[], false), None);
    }

    #[test]
    fn the_position_runs_on_while_playing() {
        let second = 10_000_000i64;
        let now = now_ticks();
        // Reported 30 s in, two seconds ago, playing: 32 s of a 3-minute track.
        assert_eq!(position_now(30 * second, 0, 180 * second, now - 2 * second, now, true), (32_000, 180_000));
        // Paused: where it stopped.
        assert_eq!(position_now(30 * second, 0, 180 * second, now - 2 * second, now, false), (30_000, 180_000));
        // Never past the end, never before the start.
        assert_eq!(position_now(179 * second, 0, 180 * second, now - 60 * second, now, true), (180_000, 180_000));
        assert_eq!(position_now(-5 * second, 0, 180 * second, now, now, true), (0, 180_000));
        // No timeline (a stream): the position alone, no length.
        assert_eq!(position_now(0, 0, 0, 0, now, true), (0, 0));
        // An update time in the future (a wrong clock) adds nothing.
        assert_eq!(position_now(10 * second, 0, 100 * second, now + 60 * second, now, true), (10_000, 100_000));
        // A timeline that starts later than 0.
        assert_eq!(position_now(15 * second, 5 * second, 65 * second, now, now, false), (10_000, 60_000));
    }

    #[test]
    fn art_is_scaled_down_by_averaging() {
        // A 4×4 square: the left half red, the right half blue, opaque; at 2×2 each pixel is one colour, at 1×1 their mix.
        let mut src = Vec::new();
        for _y in 0..4 {
            for x in 0..4 {
                src.extend_from_slice(if x < 2 { &[255, 0, 0, 255] } else { &[0, 0, 255, 255] });
            }
        }
        let half = scale_square(&src, 4, 2);
        assert_eq!(&half[0..4], &[255, 0, 0, 255]);
        assert_eq!(&half[4..8], &[0, 0, 255, 255]);
        let one = scale_square(&src, 4, 1);
        assert_eq!(one, vec![128, 0, 128, 255]);
        // A size that doesn't divide it: three pixels from four, every one opaque.
        let three = scale_square(&src, 4, 3);
        assert_eq!(three.len(), 3 * 3 * 4);
        assert!(three.chunks(4).all(|p| p[3] == 255));
        assert_eq!(&three[0..4], &[255, 0, 0, 255]);
        assert_eq!(&three[8..12], &[0, 0, 255, 255]);
        // Same size: as it is.
        assert_eq!(scale_square(&src, 4, 4), src);
    }

    #[test]
    fn a_new_track_drops_the_old_art_and_checks_its_own_a_few_times() {
        let mut cache = ArtCache::default();
        let picture = |shade: u8| vec![shade; (ART_SOURCE * ART_SOURCE * 4) as usize];
        assert!(cache.poll("a"), "a new track's art is read at once");
        cache.read(Some(picture(10)));
        let first = cache.version();
        assert_ne!(first, 0);
        assert!(cache.poll("a"));
        cache.read(Some(picture(10)));
        assert_eq!(cache.version(), first, "the same picture keeps its version");
        assert!(cache.poll("a"));
        cache.read(Some(picture(20)));
        let second = cache.version();
        assert_ne!(second, first, "the app sent its real picture a moment later");
        cache.read(None);
        assert_eq!(cache.version(), second, "a picture missing for a moment doesn't flicker");
        let checks: Vec<bool> = (0..12).map(|_| cache.poll("a")).collect();
        assert_eq!(checks.iter().filter(|c| **c).count(), 2, "then only the later checks: {checks:?}");
        assert!(cache.poll("b"));
        assert_eq!(cache.version(), 0, "the next track's art isn't the last one's");
        assert!(cache.art(64).is_none());
        cache.read(Some(picture(30)));
        let art = cache.art(64).unwrap();
        assert_eq!((art.version, art.width, art.height), (cache.version(), 64, 64));
        assert_eq!(art.rgba.len(), (64 * 64 * 4usize).div_ceil(3) * 4, "base64 of 64×64 RGBA");
        assert_eq!(cache.art(100_000).unwrap().width, ART_SOURCE, "never larger than it's kept");
        assert_eq!(cache.art(0).unwrap().width, ART_MIN);
    }

    #[test]
    fn answers_serialise_for_the_game() {
        let reply = MediaReply {
            supported: true,
            active: true,
            playing: true,
            title: "Midnight City".into(),
            artist: "M83".into(),
            app: "Spotify".into(),
            position_ms: 96_000,
            duration_ms: 243_000,
            art_version: 3,
            can_next: true,
            ..Default::default()
        };
        let value = serde_json::to_value(&reply).unwrap();
        assert_eq!(value["positionMs"], 96_000);
        assert_eq!(value["artVersion"], 3);
        assert_eq!(value["canNext"], true);
        assert!(value.get("reason").is_none(), "no reason when it works");
        assert_eq!(serde_json::to_value(unsupported(NEEDS_WINDOWS)).unwrap()["reason"], json!(NEEDS_WINDOWS));
        assert_eq!(serde_json::from_value::<MediaAction>(json!("previous")).unwrap(), MediaAction::Previous);
    }

    #[cfg(not(windows))]
    #[test]
    fn other_systems_say_windows_is_needed() {
        assert_eq!(read(false), Ok(unsupported(NEEDS_WINDOWS)));
        assert!(art(64).is_err());
        assert!(control(MediaAction::Toggle, None, false).is_err());
    }
}
