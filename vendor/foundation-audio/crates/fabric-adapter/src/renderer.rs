//! Renderer-side protocol handling + local foundation.audio sink.
//!
//! Boundary:
//! - Protocol state (generation, assets, barrier) lives here / ability engine.
//! - Decode and device output go through `PlaybackEngine` only when the
//!   `engine-adapter` feature is enabled.
//! - GroupInstant → local conversion uses the injected [`GroupClock`] (fabric
//!   SessionClock bridge). Local resume uses process monotonic time only.

use crate::assets::{AssetAssembler, AssetBlob, AssetCache};
#[cfg(feature = "engine-adapter")]
use crate::protocol::AudioFeedbackDatagram;
use crate::protocol::{AudioControlMessage, AudioStreamParameters, ContentHash, TrackMetaWire};
use crate::time::{GroupClock, GroupInstantNs, LocalInstantNs, ProcessMonotonicClock};
use crate::transport::{MediaStreamBody, read_asset_stream_object};
#[cfg(feature = "engine-adapter")]
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

#[cfg(feature = "engine-adapter")]
use crate::protocol::AudioPlaybackState;
#[cfg(feature = "engine-adapter")]
use engine::PlaybackEngine;
#[cfg(feature = "engine-adapter")]
use std::sync::Arc;
#[cfg(feature = "engine-adapter")]
use std::thread;
#[cfg(feature = "engine-adapter")]
use types::{PlayerState, TrackMetadata};

/// Renderer protocol state machine without I/O threads.
pub struct AudioRendererEngine<C: GroupClock> {
    clock: C,
    mono: ProcessMonotonicClock,
    assets: Mutex<AssetCache>,
    assembler: Mutex<AssetAssembler>,
    generation: AtomicU64,
    pending_start_local_ns: AtomicU64,
    track_meta: Mutex<Option<TrackMetaWire>>,
    cover: Mutex<Option<Vec<u8>>>,
    cover_url: Mutex<Option<String>>,
    lyrics: Mutex<Option<String>>,
    /// Zero-probe decode parameters from the latest `TrackAnnounce` (task 1).
    decode_params: Mutex<Option<AudioStreamParameters>>,
    /// Container hint is useful even when the source cannot provide the full
    /// codec parameter set. It still lets FFmpeg skip format guessing and bound
    /// stream analysis instead of falling back to the 32 MiB blind probe.
    container_hint: Mutex<Option<String>>,
    /// Playhead handover position from the latest `TrackAnnounce`.
    start_position_ms: AtomicU64,
    participant_id: String,
}

impl<C: GroupClock> AudioRendererEngine<C> {
    pub fn new(clock: C, participant_id: impl Into<String>) -> Self {
        Self {
            clock,
            mono: ProcessMonotonicClock::new(),
            assets: Mutex::new(AssetCache::new()),
            assembler: Mutex::new(AssetAssembler::new()),
            generation: AtomicU64::new(0),
            pending_start_local_ns: AtomicU64::new(0),
            track_meta: Mutex::new(None),
            cover: Mutex::new(None),
            cover_url: Mutex::new(None),
            lyrics: Mutex::new(None),
            decode_params: Mutex::new(None),
            container_hint: Mutex::new(None),
            start_position_ms: AtomicU64::new(0),
            participant_id: participant_id.into(),
        }
    }

    pub fn participant_id(&self) -> &str {
        &self.participant_id
    }

    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    pub fn pending_start_local_ns(&self) -> Option<u64> {
        let value = self.pending_start_local_ns.load(Ordering::Acquire);
        (value > 0).then_some(value)
    }

    pub fn current_meta(&self) -> Option<TrackMetaWire> {
        self.track_meta.lock().unwrap().clone()
    }

    pub fn current_cover(&self) -> Option<Vec<u8>> {
        self.cover.lock().unwrap().clone()
    }

    /// External cover URL from the current `TrackAnnounce`, if any (task 2). The
    /// app fetches artwork off-fabric; the renderer never pulls cover bytes.
    pub fn current_cover_url(&self) -> Option<String> {
        self.cover_url.lock().unwrap().clone()
    }

    /// Exact decode parameters announced for the current track, used to start
    /// FFmpeg without a blind stream probe (task 1). `None` ⇒ fall back to probing.
    pub fn decode_params(&self) -> Option<AudioStreamParameters> {
        self.decode_params.lock().unwrap().clone()
    }

    /// Playhead handover position (ms) announced for the current track.
    pub fn start_position_ms(&self) -> u64 {
        self.start_position_ms.load(Ordering::Acquire)
    }

    /// Read a framed asset from an inbound unidirectional data-plane stream and
    /// install it as cover/lyrics (task 2). Returns the verified content hash.
    pub fn accept_asset_stream<R: Read>(&self, mut reader: R) -> Result<ContentHash, String> {
        let (hash, mime, bytes) =
            read_asset_stream_object(&mut reader).map_err(|e| e.to_string())?;
        if crate::assets::hash_bytes(&bytes) != hash {
            return Err("asset hash mismatch".into());
        }
        self.install_asset(hash, AssetBlob { mime, bytes });
        Ok(hash)
    }

    pub fn current_lyrics(&self) -> Option<String> {
        self.lyrics.lock().unwrap().clone().or_else(|| {
            self.track_meta
                .lock()
                .unwrap()
                .as_ref()
                .and_then(|m| m.lyrics.clone())
        })
    }

    /// Handle one control message. Returns optional outbound replies.
    pub fn handle_control(&self, message: AudioControlMessage) -> Vec<AudioControlMessage> {
        match message {
            AudioControlMessage::TrackAnnounce {
                generation,
                meta,
                cover_hash,
                lyrics_hash,
                cover_url,
                media,
            } => {
                self.generation.store(generation, Ordering::Release);
                // A new generation must not inherit the preceding track's
                // artwork while its own asset is still in flight.
                *self.cover.lock().unwrap() = None;
                *self.lyrics.lock().unwrap() = None;
                if let Some(lyrics) = meta.lyrics.clone() {
                    *self.lyrics.lock().unwrap() = Some(lyrics);
                }
                *self.track_meta.lock().unwrap() = Some(meta);
                // Zero-probe: remember exact decode params so media attach can
                // configure FFmpeg without a 32 MiB blind probe (task 1).
                *self.container_hint.lock().unwrap() = media.container_hint.clone();
                *self.decode_params.lock().unwrap() = media.decode;
                self.start_position_ms
                    .store(media.start_position_ms, Ordering::Release);
                // Async cover: an external URL lets the app fetch artwork
                // off-fabric; we never request the bytes over control (task 2).
                *self.cover_url.lock().unwrap() = cover_url;
                let mut replies = Vec::new();
                {
                    let cache = self.assets.lock().unwrap();
                    if let Some(hash) = cover_hash {
                        if let Some(blob) = cache.get(&hash) {
                            *self.cover.lock().unwrap() = Some(blob.bytes.clone());
                        } else {
                            replies.push(AudioControlMessage::AssetRequest { hash });
                        }
                    }
                    if let Some(hash) = lyrics_hash {
                        if let Some(blob) = cache.get(&hash) {
                            if let Ok(text) = String::from_utf8(blob.bytes.clone()) {
                                *self.lyrics.lock().unwrap() = Some(text);
                            }
                        } else {
                            replies.push(AudioControlMessage::AssetRequest { hash });
                        }
                    }
                }
                replies
            }
            AudioControlMessage::AssetOffer { hash, mime, bytes } => {
                let blob = self
                    .assembler
                    .lock()
                    .unwrap()
                    .accept_offer(hash, mime, bytes);
                self.install_asset(hash, blob);
                Vec::new()
            }
            AudioControlMessage::AssetChunk {
                hash,
                mime,
                index,
                total,
                data,
            } => {
                if let Some(blob) = self
                    .assembler
                    .lock()
                    .unwrap()
                    .accept_chunk(hash, mime, index, total, data)
                {
                    self.install_asset(hash, blob);
                }
                Vec::new()
            }
            AudioControlMessage::PrepareSynced {
                generation,
                activate_at_group_ns,
                start_at_local_ns,
            } => {
                if generation != self.generation() {
                    return vec![AudioControlMessage::Reject {
                        generation,
                        participant_id: self.participant_id.clone(),
                        reason: "stale generation".into(),
                    }];
                }
                let local = if let Some(local) = start_at_local_ns {
                    Some(local)
                } else {
                    self.clock
                        .group_to_local(GroupInstantNs(activate_at_group_ns))
                        .ok()
                        .map(|LocalInstantNs(v)| v)
                };
                match local {
                    Some(local) => {
                        self.pending_start_local_ns.store(local, Ordering::Release);
                        vec![AudioControlMessage::Ready {
                            generation,
                            participant_id: self.participant_id.clone(),
                            uncertainty_ns: self.clock.uncertainty_ns(),
                            // Caller should refresh buffered_ms after media attach.
                            buffered_ms: 0,
                        }]
                    }
                    None => vec![AudioControlMessage::Reject {
                        generation,
                        participant_id: self.participant_id.clone(),
                        reason: "clock mapping failed".into(),
                    }],
                }
            }
            AudioControlMessage::Commit {
                generation,
                activate_at_group_ns,
            } => {
                if generation == self.generation()
                    && let Ok(LocalInstantNs(local)) = self
                        .clock
                        .group_to_local(GroupInstantNs(activate_at_group_ns))
                {
                    self.pending_start_local_ns.store(local, Ordering::Release);
                }
                Vec::new()
            }
            AudioControlMessage::AssetStreamAnnounce { .. } => {
                // Bytes arrive on the data plane; see `accept_asset_stream`. The
                // control-plane announce carries no payload to act on here.
                Vec::new()
            }
            AudioControlMessage::Abort { .. } | AudioControlMessage::Stop { .. } => {
                self.pending_start_local_ns.store(0, Ordering::Release);
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// After media is attached and buffering, emit a Ready with real buffer depth.
    pub fn ready_with_buffer(&self, buffered_ms: u32) -> AudioControlMessage {
        AudioControlMessage::Ready {
            generation: self.generation(),
            participant_id: self.participant_id.clone(),
            uncertainty_ns: self.clock.uncertainty_ns(),
            buffered_ms,
        }
    }

    pub fn take_start_at_local_ns(&self) -> Option<u64> {
        let value = self.pending_start_local_ns.swap(0, Ordering::AcqRel);
        (value > 0).then_some(value)
    }

    fn install_asset(&self, hash: ContentHash, blob: AssetBlob) {
        let is_image = blob.mime.starts_with("image/");
        let is_text = blob.mime.starts_with("text/");
        if is_image {
            *self.cover.lock().unwrap() = Some(blob.bytes.clone());
        }
        if is_text {
            if let Ok(text) = String::from_utf8(blob.bytes.clone()) {
                *self.lyrics.lock().unwrap() = Some(text);
            }
        }
        self.assets.lock().unwrap().insert(hash, blob);
    }

    pub fn mono(&self) -> &ProcessMonotonicClock {
        &self.mono
    }
}

/// Bridge that turns an inbound media stream into a [`MediaByteSource`].
pub struct StreamMediaSource<R: Read + Send> {
    inner: MediaStreamBody<R>,
    position: u64,
}

impl<R: Read + Send> StreamMediaSource<R> {
    pub fn open(reader: R) -> std::io::Result<Self> {
        Ok(Self {
            inner: MediaStreamBody::open(reader)?,
            position: 0,
        })
    }

    pub fn generation(&self) -> u64 {
        self.inner.generation
    }

    /// Media-timeline position (ms) at which this stream's byte 0 begins.
    pub fn start_position_ms(&self) -> u64 {
        self.inner.start_position_ms
    }
}

impl<R: Read + Send> Read for StreamMediaSource<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        self.position = self.position.saturating_add(n as u64);
        Ok(n)
    }
}

#[cfg(feature = "engine-adapter")]
impl<R: Read + Send + 'static> util::stream_io::MediaByteSource for StreamMediaSource<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        Read::read(self, buf)
    }

    fn seek(&mut self, _pos: std::io::SeekFrom) -> std::io::Result<u64> {
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "progressive fabric media stream is not seekable",
        ))
    }

    fn is_seekable(&self) -> bool {
        false
    }

    fn byte_len(&self) -> Option<u64> {
        (self.inner.byte_length > 0).then_some(self.inner.byte_length)
    }
}

/// Map the wire decode parameters onto foundation.audio's stream-open params
/// (task 1). A `None` container makes the engine fall back to a bounded/blind
/// probe, so this is always safe to pass through.
#[cfg(feature = "engine-adapter")]
fn to_stream_open_params(p: &AudioStreamParameters) -> util::stream_io::StreamOpenParams {
    util::stream_io::StreamOpenParams {
        container: p.container.clone(),
        codec_id: p.codec_id as i32,
        sample_rate: p.sample_rate as i32,
        channels: i32::from(p.channels),
        channel_layout: p.channel_layout,
        sample_format: p.sample_format,
        bit_rate: p.bit_rate,
        bits_per_coded_sample: p.bits_per_coded_sample as i32,
        block_align: p.block_align as i32,
        frame_size: p.frame_size as i32,
        extradata: p.extradata.clone(),
        probe_bytes: None,
    }
}

/// Local sink: protocol engine → PlaybackEngine.
#[cfg(feature = "engine-adapter")]
pub struct LocalAudioRenderAdapter<C: GroupClock> {
    pub engine: Arc<PlaybackEngine>,
    pub protocol: AudioRendererEngine<C>,
}

#[cfg(feature = "engine-adapter")]
impl<C: GroupClock + 'static> LocalAudioRenderAdapter<C> {
    pub fn new(engine: Arc<PlaybackEngine>, clock: C, participant_id: impl Into<String>) -> Self {
        Self {
            engine,
            protocol: AudioRendererEngine::new(clock, participant_id),
        }
    }

    /// Handle one control payload. Protocol replies (Ready/Reject/AssetRequest)
    /// are returned as JSON arrays when multiple; simple status uses
    /// [`SimpleInvokeResponse`].
    pub fn handle_control_json(&self, payload: &[u8]) -> Vec<u8> {
        match serde_json::from_slice::<AudioControlMessage>(payload) {
            Ok(message) => {
                let replies = self.protocol.handle_control(message.clone());
                match message {
                    AudioControlMessage::Play { .. } => self.engine.resume(),
                    AudioControlMessage::Pause { .. } => self.engine.pause(),
                    AudioControlMessage::Stop { .. } => self.engine.stop(),
                    AudioControlMessage::Seek { position_ms, .. } => {
                        // Progressive streams cannot seek locally: the source
                        // remuxes from the new position on the same stream. Drop
                        // the buffered pre-seek audio and rebase the reported
                        // position so UI/lyrics track the content that arrives.
                        if !self.engine.seek(position_ms) {
                            let _ = self.engine.rebase_stream_position(position_ms);
                        }
                    }
                    AudioControlMessage::PrepareSynced { .. } => {
                        // Media may already be attached and parked paused; when
                        // the barrier arrives after attach, schedule the resume
                        // here — attach_media_stream will never see it.
                        if self.engine.state() == PlayerState::Paused {
                            if let Some(start_at) = self.protocol.take_start_at_local_ns() {
                                self.schedule_resume_at(start_at);
                            }
                        }
                    }
                    _ => {}
                }
                if !replies.is_empty() {
                    return serde_json::to_vec(&replies).unwrap_or_else(|_| b"[]".to_vec());
                }
                serde_json::to_vec(&self.simple_status(true, None))
                    .unwrap_or_else(|_| br#"{"ok":false}"#.to_vec())
            }
            Err(error) => serde_json::to_vec(&self.simple_status(false, Some(error.to_string())))
                .unwrap_or_else(|_| br#"{"ok":false}"#.to_vec()),
        }
    }

    /// Attach progressive media stream, apply metadata overlay, pause, and
    /// schedule resume at the barrier local time when present.
    pub fn attach_media_stream<R: Read + Send + 'static>(&self, reader: R) -> Result<(), String> {
        let source = StreamMediaSource::open(reader).map_err(|e| e.to_string())?;
        if source.generation() != 0 && source.generation() != self.protocol.generation() {
            return Err("media generation mismatch".into());
        }
        let overlay = self.presentation_metadata();
        // Zero-probe: if the announce carried exact params, start FFmpeg with the
        // known demuxer + bounded probe instead of a 32 MiB blind scan (task 1).
        // Two ways a stream can start mid-track:
        // - the stream itself begins at the playhead (remuxed cast handover or a
        //   seek restart): the header carries that position, nothing to skip,
        //   only the reported timeline needs rebasing;
        // - the stream is the raw object from byte 0 and the announce asks to
        //   start at `start_position_ms`: decode-and-discard up to there.
        let stream_position_ms = source.start_position_ms();
        let skip_ms = if stream_position_ms > 0 {
            0
        } else {
            self.protocol.start_position_ms()
        };
        let was_playing = self.engine.state() == PlayerState::Playing;
        let open_params = self
            .protocol
            .decode_params()
            .map(|params| to_stream_open_params(&params))
            .or_else(|| {
                self.protocol
                    .container_hint
                    .lock()
                    .unwrap()
                    .clone()
                    .map(|container| util::stream_io::StreamOpenParams {
                        container: Some(container),
                        ..Default::default()
                    })
            });
        let ok = match open_params {
            Some(params) => self.engine.play_media_stream_with_params(
                Box::new(source),
                &params,
                Some(overlay),
                true,
                skip_ms,
            ),
            None => self
                .engine
                .play_media_stream(Box::new(source), Some(overlay), true, skip_ms),
        };
        if !ok {
            return Err("failed to start stream decode".into());
        }
        if stream_position_ms > 0 {
            let _ = self.engine.rebase_stream_position(stream_position_ms);
        }
        if let Some(start_at) = self.protocol.take_start_at_local_ns() {
            self.schedule_resume_at(start_at);
        } else if was_playing && stream_position_ms > 0 {
            // Seek restart: the previous stream was already playing at the
            // barrier; the replacement continues immediately.
            self.engine.resume();
        }
        Ok(())
    }

    pub fn presentation_metadata(&self) -> TrackMetadata {
        let meta = self.protocol.current_meta().unwrap_or_default();
        TrackMetadata {
            title: meta.title,
            artist: meta.artist,
            album: meta.album,
            album_artist: meta.album_artist,
            genre: meta.genre,
            year: meta.year,
            track_number: meta.track_number,
            disc_number: meta.disc_number,
            duration_ms: meta.duration_ms,
            sample_rate: meta.sample_rate,
            channels: meta.channels,
            bit_rate: meta.bit_rate,
            replay_gain_db: meta.replay_gain_db,
            cover_art: self.protocol.current_cover(),
            lyrics: self.protocol.current_lyrics(),
        }
    }

    pub fn feedback_datagram(&self) -> AudioFeedbackDatagram {
        let shared = self.engine.shared_state();
        let sample_rate = shared.sample_rate.load(Ordering::Relaxed);
        let current_frame = shared.current_frame.load(Ordering::Relaxed);
        let position_ms = if sample_rate == 0 {
            0
        } else {
            current_frame.saturating_mul(1000) / u64::from(sample_rate)
        };
        let buffered_samples = shared.buffered_samples.load(Ordering::Relaxed);
        let channels = self
            .engine
            .current_metadata()
            .map(|meta| u64::from(meta.channels.max(1)))
            .unwrap_or(2);
        let buffered_ms = if sample_rate == 0 {
            0
        } else {
            buffered_samples / channels * 1000 / u64::from(sample_rate)
        };
        AudioFeedbackDatagram {
            generation: self.protocol.generation(),
            state: self.engine.state().into(),
            position_ms,
            duration_ms: shared.duration_ms.load(Ordering::Relaxed),
            buffered_ms: u32::try_from(buffered_ms).unwrap_or(u32::MAX),
            sample_rate,
            underruns: u32::try_from(shared.underruns.load(Ordering::Relaxed)).unwrap_or(u32::MAX),
        }
    }

    fn schedule_resume_at(&self, start_at_local_ns: u64) {
        // Use the same ProcessMonotonicClock origin as PrepareSynced mapping.
        // A mapped instant from a mismatched clock domain could land arbitrarily
        // far in the future; starting slightly unsynced beats never starting,
        // so cap the wait instead of trusting the value blindly.
        const MAX_RESUME_WAIT_NS: u64 = 10_000_000_000;
        let engine = Arc::clone(&self.engine);
        let mono = self.protocol.mono().clone();
        thread::spawn(move || {
            let now = mono.now_ns();
            let target = start_at_local_ns.min(now.saturating_add(MAX_RESUME_WAIT_NS));
            mono.sleep_until_ns(target);
            engine.resume();
        });
    }

    pub fn simple_status(&self, ok: bool, error: Option<String>) -> SimpleInvokeResponse {
        SimpleInvokeResponse {
            ok,
            error,
            status: Some(self.feedback_datagram()),
        }
    }
}

#[cfg(feature = "engine-adapter")]
impl From<PlayerState> for AudioPlaybackState {
    fn from(value: PlayerState) -> Self {
        match value {
            PlayerState::Stopped => Self::Stopped,
            PlayerState::Playing => Self::Playing,
            PlayerState::Paused => Self::Paused,
            PlayerState::Buffering => Self::Buffering,
        }
    }
}

/// Compatibility response for simple invoke-style platform adapters.
#[cfg(feature = "engine-adapter")]
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct SimpleInvokeResponse {
    pub ok: bool,
    pub error: Option<String>,
    pub status: Option<AudioFeedbackDatagram>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        AudioStreamParameters, MediaDeliveryPolicy, MediaDescriptor, TrackMetaWire,
    };
    use crate::time::IdentityGroupClock;

    #[test]
    fn announce_requests_missing_cover_and_keeps_metadata() {
        let renderer = AudioRendererEngine::new(IdentityGroupClock::new(), "r1");
        let replies = renderer.handle_control(AudioControlMessage::TrackAnnounce {
            generation: 7,
            meta: TrackMetaWire {
                title: Some("Title".into()),
                artist: Some("Artist".into()),
                album: Some("Album".into()),
                lyrics: Some("verse".into()),
                duration_ms: 12_000,
                ..Default::default()
            },
            cover_hash: Some([3u8; 32]),
            lyrics_hash: None,
            cover_url: None,
            media: MediaDescriptor {
                container_hint: Some("opus".into()),
                codec_hint: Some("opus".into()),
                byte_length: Some(50_000),
                content_hash: None,
                delivery: MediaDeliveryPolicy::PerPeerUnicast,
                decode: Some(AudioStreamParameters {
                    codec_id: 86_076,
                    codec_name: Some("opus".into()),
                    sample_rate: 48_000,
                    channels: 2,
                    ..Default::default()
                }),
                start_position_ms: 0,
            },
        });
        assert_eq!(renderer.generation(), 7);
        assert_eq!(
            renderer.current_meta().unwrap().title.as_deref(),
            Some("Title")
        );
        assert_eq!(renderer.current_lyrics().as_deref(), Some("verse"));
        // Zero-probe params captured for the media-attach fast path (task 1).
        assert!(renderer.decode_params().unwrap().is_decodable());
        assert!(matches!(
            replies.as_slice(),
            [AudioControlMessage::AssetRequest { hash }] if *hash == [3u8; 32]
        ));
    }

    #[test]
    fn external_cover_url_suppresses_asset_request() {
        let renderer = AudioRendererEngine::new(IdentityGroupClock::new(), "r1");
        let replies = renderer.handle_control(AudioControlMessage::TrackAnnounce {
            generation: 2,
            meta: TrackMetaWire::default(),
            // Source drops the hash when it ships a URL, so no bytes are pulled.
            cover_hash: None,
            lyrics_hash: None,
            cover_url: Some("https://cdn.example/a.jpg".into()),
            media: MediaDescriptor {
                container_hint: None,
                codec_hint: None,
                byte_length: None,
                content_hash: None,
                delivery: MediaDeliveryPolicy::PerPeerUnicast,
                decode: None,
                start_position_ms: 0,
            },
        });
        assert!(replies.is_empty());
        assert_eq!(
            renderer.current_cover_url().as_deref(),
            Some("https://cdn.example/a.jpg")
        );
    }

    #[test]
    fn accepts_asset_bytes_from_a_data_plane_stream() {
        use crate::transport::write_asset_stream_object;
        let renderer = AudioRendererEngine::new(IdentityGroupClock::new(), "r1");
        let bytes = vec![9u8; 4096];
        let hash = crate::assets::hash_bytes(&bytes);
        let mut framed = Vec::new();
        write_asset_stream_object(&mut framed, hash, "image/jpeg", &bytes).unwrap();
        let got = renderer
            .accept_asset_stream(std::io::Cursor::new(framed))
            .unwrap();
        assert_eq!(got, hash);
        assert_eq!(renderer.current_cover(), Some(bytes));
    }

    #[test]
    fn prepare_synced_maps_group_instant_to_local_ready() {
        let renderer = AudioRendererEngine::new(IdentityGroupClock::new(), "r1");
        renderer.handle_control(AudioControlMessage::TrackAnnounce {
            generation: 1,
            meta: TrackMetaWire::default(),
            cover_hash: None,
            lyrics_hash: None,
            cover_url: None,
            media: MediaDescriptor {
                container_hint: None,
                codec_hint: None,
                byte_length: None,
                content_hash: None,
                delivery: MediaDeliveryPolicy::PerPeerUnicast,
                decode: None,
                start_position_ms: 0,
            },
        });
        let replies = renderer.handle_control(AudioControlMessage::PrepareSynced {
            generation: 1,
            activate_at_group_ns: 1_000_000,
            start_at_local_ns: None,
        });
        assert!(matches!(
            replies.as_slice(),
            [AudioControlMessage::Ready { generation: 1, .. }]
        ));
        assert_eq!(renderer.pending_start_local_ns(), Some(1_000_000));
    }
}
