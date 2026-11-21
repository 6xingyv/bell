//! Source-side protocol engine helpers.
//!
//! Depends only on abstract transports + group clock mapping. Does not depend
//! on foundation.audio decode/playback (source may stream file bytes without
//! decoding). Multi-device sync uses GroupInstant + barrier-style Ready/Commit
//! that the fabric SessionClock/Barrier stack already provides semantics for.

use crate::assets::{
    AssetBlob, AnnouncedAssets, asset_transfer_messages, hash_bytes, prepare_announce_assets,
};
use crate::delivery::{DeliveryPlan, DeliveryPlanInput, MediaStreamHeader, plan_delivery};
use crate::protocol::{
    AudioControlMessage, AudioStreamParameters, MediaDeliveryPolicy, MediaDescriptor, TrackMetaWire,
};
use crate::time::{GroupClock, GroupInstantNs, LocalInstantNs};
use crate::transport::{
    write_asset_stream_object, write_media_object, ControlTransport, MediaStreamTransport,
};
use std::io::{self, Read, Write};
use std::time::Duration;

/// Size of the streaming fan-out / media copy buffer. Media is read and written
/// in bounded blocks so the source never buffers a whole object in RAM.
const STREAM_CHUNK_BYTES: usize = 64 * 1024;

#[derive(Clone, Debug)]
pub struct TrackOffer {
    pub generation: u64,
    pub meta: TrackMetaWire,
    pub cover: Option<Vec<u8>>,
    pub cover_mime: String,
    pub lyrics: Option<String>,
    pub media_byte_length: Option<u64>,
    pub media_content_hash: Option<[u8; 32]>,
    pub container_hint: Option<String>,
    pub codec_hint: Option<String>,
    /// Exact decode parameters for zero-probe startup (task 1). Populated by the
    /// source after it demuxes the local file to announce the track.
    pub decode: Option<AudioStreamParameters>,
    /// Out-of-band cover source; when set, renderers may fetch artwork off-fabric
    /// and the source never has to push cover bytes (task 2).
    pub cover_url: Option<String>,
    /// Playhead handover: position (ms) the renderer should start at. Leave `0`
    /// when the media stream itself already begins at the playhead.
    pub start_position_ms: u64,
}

#[derive(Clone, Debug)]
pub struct SourceSessionConfig {
    pub renderer_ids: Vec<String>,
    pub delivery_preferred: MediaDeliveryPolicy,
    pub allow_relay: bool,
    pub allow_multicast: bool,
    /// How far in the future to schedule synchronized start (group time).
    pub start_delay: Duration,
    /// Minimum buffered media reported by each renderer before commit.
    pub min_buffer_ms: u32,
    pub max_uncertainty_ns: u64,
    /// When there is at most one renderer, skip the multi-device Prepare/Ready/
    /// Commit consensus wait (task 4). The scheduled activation still prevents an
    /// attach/start race, but the source does not block on a network round-trip
    /// whose only purpose is quorum across ≥2 devices. Ignored for N≥2.
    pub single_device_fast_start: bool,
}

impl Default for SourceSessionConfig {
    fn default() -> Self {
        Self {
            renderer_ids: Vec::new(),
            delivery_preferred: MediaDeliveryPolicy::PerPeerUnicast,
            allow_relay: false,
            allow_multicast: false,
            start_delay: Duration::from_millis(400),
            min_buffer_ms: 80,
            max_uncertainty_ns: 5_000_000,
            single_device_fast_start: true,
        }
    }
}

#[derive(Debug)]
pub enum SourceError<E> {
    Transport(E),
    Io(io::Error),
    Clock,
    NotReady(&'static str),
}

/// Orchestrates announce → asset repair → media fanout plan → barrier start.
pub struct AudioSourceEngine<C, T, M> {
    clock: C,
    control: T,
    media: M,
    config: SourceSessionConfig,
}

impl<C, T, M> AudioSourceEngine<C, T, M>
where
    C: GroupClock,
    T: ControlTransport,
    M: MediaStreamTransport,
{
    pub fn new(clock: C, control: T, media: M, config: SourceSessionConfig) -> Self {
        Self {
            clock,
            control,
            media,
            config,
        }
    }

    pub fn plan_for_offer(&self, offer: &TrackOffer, announced: &AnnouncedAssets) -> DeliveryPlan {
        let cover_bytes = announced.cover.as_ref().map(|b| b.bytes.len() as u64);
        let lyrics_bytes = announced
            .lyrics_asset
            .as_ref()
            .map(|b| b.bytes.len() as u64);
        plan_delivery(&DeliveryPlanInput {
            renderer_count: self.config.renderer_ids.len(),
            media_bytes: offer.media_byte_length,
            cover_bytes,
            lyrics_bytes,
            // Unknown until peers respond; optimistic zero cache hits.
            cover_cache_hits: 0,
            lyrics_cache_hits: 0,
            allow_relay: self.config.allow_relay,
            allow_multicast: self.config.allow_multicast,
            preferred: self.config.delivery_preferred,
        })
    }

    /// Announce track metadata and content hashes to all renderers.
    pub fn announce_track(
        &self,
        offer: &TrackOffer,
    ) -> Result<(AnnouncedAssets, DeliveryPlan), SourceError<T::Error>> {
        let announced = prepare_announce_assets(
            offer.meta.clone(),
            offer.cover.clone(),
            &offer.cover_mime,
            offer.lyrics.clone(),
        );
        let plan = self.plan_for_offer(offer, &announced);
        let media = MediaDescriptor {
            container_hint: offer.container_hint.clone(),
            codec_hint: offer.codec_hint.clone(),
            byte_length: offer.media_byte_length,
            content_hash: offer.media_content_hash,
            delivery: plan.policy,
            decode: offer.decode.clone(),
            start_position_ms: offer.start_position_ms,
        };
        // An external cover URL makes the hash-pull redundant: drop the hash so
        // renderers fetch off-fabric instead of asking us for the bytes.
        let cover_hash = if offer.cover_url.is_some() {
            None
        } else {
            announced.cover_hash
        };
        let message = AudioControlMessage::TrackAnnounce {
            generation: offer.generation,
            meta: announced.meta.clone(),
            cover_hash,
            lyrics_hash: announced.lyrics_hash,
            cover_url: offer.cover_url.clone(),
            media,
        };
        for renderer in &self.config.renderer_ids {
            self.control
                .send_control(Some(renderer), &message)
                .map_err(SourceError::Transport)?;
        }
        Ok((announced, plan))
    }

    /// Serve content-addressed assets only to peers that request them.
    pub fn fulfill_asset_request(
        &self,
        participant_id: &str,
        hash: [u8; 32],
        blob: &AssetBlob,
    ) -> Result<(), SourceError<T::Error>> {
        if hash_bytes(&blob.bytes) != hash {
            return Err(SourceError::NotReady("asset hash mismatch"));
        }
        for message in asset_transfer_messages(hash, blob) {
            self.control
                .send_control(Some(participant_id), &message)
                .map_err(SourceError::Transport)?;
        }
        Ok(())
    }

    /// Push compressed media according to the delivery plan.
    ///
    /// For `PerPeerUnicast`, `route_keys` should be the renderer ids.
    /// For relay/multicast, pass a single logical route key (e.g. `"media"`).
    pub fn push_media<R: Read>(
        &self,
        plan: &DeliveryPlan,
        generation: u64,
        byte_length: u64,
        mut body: R,
        route_keys: &[String],
    ) -> Result<u64, SourceError<M::Error>> {
        self.push_media_from(plan, generation, byte_length, 0, &mut body, route_keys)
    }

    /// [`push_media`](Self::push_media) with an explicit stream start position:
    /// `start_position_ms` is the media-timeline position at which `body`'s
    /// byte 0 begins. Use this to restart delivery after a `Seek` — open fresh
    /// media streams positioned at the target and let the renderers re-attach
    /// there; the old streams (and their in-flight bytes) are simply dropped.
    pub fn push_media_from(
        &self,
        plan: &DeliveryPlan,
        generation: u64,
        byte_length: u64,
        start_position_ms: u64,
        body: &mut dyn Read,
        route_keys: &[String],
    ) -> Result<u64, SourceError<M::Error>> {
        let keys: Vec<&str> = if plan.media_stream_count <= 1 {
            route_keys
                .first()
                .map(|k| vec![k.as_str()])
                .unwrap_or_else(|| vec!["media"])
        } else {
            route_keys.iter().map(String::as_str).collect()
        };

        // Fast path: a single destination is a straight header+copy, streaming
        // through `io::copy` without any intermediate buffer.
        if keys.len() == 1 {
            let mut writer = self
                .media
                .open_media_writer(keys[0])
                .map_err(SourceError::Transport)?;
            return write_media_object(
                &mut writer,
                generation,
                byte_length,
                start_position_ms,
                body,
            )
            .map_err(SourceError::Io);
        }

        // Multi-peer unicast: open every writer, then pump the compressed
        // container in bounded chunks to all of them at once. No read_to_end —
        // the source never holds the whole object in RAM, never re-encodes, and
        // the first bytes reach every renderer as soon as they are read instead
        // of only after the entire file has been buffered.
        let mut writers = Vec::with_capacity(keys.len());
        for key in keys {
            writers.push(
                self.media
                    .open_media_writer(key)
                    .map_err(SourceError::Transport)?,
            );
        }

        let header = MediaStreamHeader {
            generation,
            flags: MediaStreamHeader::FLAG_COMPRESSED_CONTAINER,
            byte_length,
            start_position_ms,
        }
        .to_bytes();
        for writer in &mut writers {
            writer.write_all(&header).map_err(SourceError::Io)?;
        }

        let mut buf = vec![0u8; STREAM_CHUNK_BYTES];
        let mut written = 0u64;
        loop {
            let read = body.read(&mut buf).map_err(SourceError::Io)?;
            if read == 0 {
                break;
            }
            for writer in &mut writers {
                writer.write_all(&buf[..read]).map_err(SourceError::Io)?;
            }
            written += read as u64;
        }
        for writer in &mut writers {
            writer.flush().map_err(SourceError::Io)?;
        }
        Ok(written)
    }

    /// Serve a content-addressed asset (cover art, long lyrics) on a dedicated
    /// unidirectional data-plane stream instead of the control plane (task 2).
    ///
    /// `writer` is a fabric `open_uni` stream. The control plane only carries a
    /// small [`AudioControlMessage::AssetStreamAnnounce`]; the multi-hundred-KB
    /// bytes ride the data plane, so `Play`/`Pause`/`Seek` never queue behind a
    /// cover transfer. Prefer this over [`fulfill_asset_request`] whenever the
    /// link can open a spare stream.
    pub fn serve_asset_stream<W: Write>(
        &self,
        participant_id: &str,
        hash: [u8; 32],
        blob: &AssetBlob,
        writer: &mut W,
    ) -> Result<(), SourceError<T::Error>> {
        if hash_bytes(&blob.bytes) != hash {
            return Err(SourceError::NotReady("asset hash mismatch"));
        }
        let announce = AudioControlMessage::AssetStreamAnnounce {
            hash,
            mime: blob.mime.clone(),
            byte_length: blob.bytes.len() as u64,
        };
        self.control
            .send_control(Some(participant_id), &announce)
            .map_err(SourceError::Transport)?;
        write_asset_stream_object(writer, hash, &blob.mime, &blob.bytes).map_err(SourceError::Io)?;
        Ok(())
    }

    /// Choose a shared GroupInstant activation time and run barrier messaging.
    ///
    /// For a single renderer (or local playback) with
    /// [`SourceSessionConfig::single_device_fast_start`], this skips the
    /// Prepare/Ready/Commit consensus round-trip (task 4): it schedules an
    /// activation and commits immediately without waiting for a network `Ready`,
    /// because quorum only matters when synchronizing ≥2 devices.
    pub fn synchronize_start(
        &self,
        generation: u64,
    ) -> Result<GroupInstantNs, SourceError<T::Error>> {
        let now_local = self.clock.local_now();
        let activate_local = LocalInstantNs(
            now_local
                .0
                .saturating_add(self.config.start_delay.as_nanos() as u64),
        );
        let activate_group = self
            .clock
            .local_to_group(activate_local)
            .map_err(|_| SourceError::Clock)?;

        if self.config.single_device_fast_start && self.config.renderer_ids.len() <= 1 {
            return self.commit_fast_start(generation, activate_group);
        }

        let prepare = AudioControlMessage::PrepareSynced {
            generation,
            activate_at_group_ns: activate_group.0,
            start_at_local_ns: None,
        };
        for renderer in &self.config.renderer_ids {
            self.control
                .send_control(Some(renderer), &prepare)
                .map_err(SourceError::Transport)?;
        }

        let mut ready = 0usize;
        // Drain ready replies for this generation. Real integrations multiplex
        // control on a task; this synchronous form is for structured tests /
        // simple adapters.
        while ready < self.config.renderer_ids.len() {
            let (_from, message) = self
                .control
                .recv_control()
                .map_err(SourceError::Transport)?;
            match message {
                AudioControlMessage::Ready {
                    generation: g,
                    uncertainty_ns,
                    buffered_ms,
                    ..
                } if g == generation => {
                    if uncertainty_ns > self.config.max_uncertainty_ns {
                        let abort = AudioControlMessage::Abort {
                            generation,
                            reason: "clock uncertainty".into(),
                        };
                        self.broadcast_control(&abort)?;
                        return Err(SourceError::NotReady("clock uncertainty"));
                    }
                    if buffered_ms < self.config.min_buffer_ms {
                        let abort = AudioControlMessage::Abort {
                            generation,
                            reason: "insufficient buffer".into(),
                        };
                        self.broadcast_control(&abort)?;
                        return Err(SourceError::NotReady("insufficient buffer"));
                    }
                    ready += 1;
                }
                AudioControlMessage::Reject {
                    generation: g,
                    reason,
                    ..
                } if g == generation => {
                    let abort = AudioControlMessage::Abort {
                        generation,
                        reason,
                    };
                    self.broadcast_control(&abort)?;
                    return Err(SourceError::NotReady("renderer rejected"));
                }
                _ => {}
            }
        }

        let commit = AudioControlMessage::Commit {
            generation,
            activate_at_group_ns: activate_group.0,
        };
        self.broadcast_control(&commit)?;
        Ok(activate_group)
    }

    /// Single-renderer / local start: announce the activation and commit in one
    /// shot, with no `Ready` wait. See [`synchronize_start`](Self::synchronize_start).
    fn commit_fast_start(
        &self,
        generation: u64,
        activate_group: GroupInstantNs,
    ) -> Result<GroupInstantNs, SourceError<T::Error>> {
        let prepare = AudioControlMessage::PrepareSynced {
            generation,
            activate_at_group_ns: activate_group.0,
            start_at_local_ns: None,
        };
        self.broadcast_control(&prepare)?;
        let commit = AudioControlMessage::Commit {
            generation,
            activate_at_group_ns: activate_group.0,
        };
        self.broadcast_control(&commit)?;
        Ok(activate_group)
    }

    /// Handle a transport command a renderer or controller routed back to the
    /// source over the bidirectional control port (task 3). The source is the
    /// single writer of group state, so it re-broadcasts the command to every
    /// renderer and returns `true` when the message was a transport command.
    ///
    /// Callers that also drive a local `PlaybackEngine` (the source is itself a
    /// renderer) should apply the same command locally.
    pub fn apply_transport_command(
        &self,
        message: &AudioControlMessage,
    ) -> Result<bool, SourceError<T::Error>> {
        match message {
            AudioControlMessage::Play { .. }
            | AudioControlMessage::Pause { .. }
            | AudioControlMessage::Stop { .. }
            | AudioControlMessage::Seek { .. } => {
                self.broadcast_control(message)?;
                Ok(true)
            }
            _ => Ok(false),
        }
    }

    fn broadcast_control(
        &self,
        message: &AudioControlMessage,
    ) -> Result<(), SourceError<T::Error>> {
        for renderer in &self.config.renderer_ids {
            self.control
                .send_control(Some(renderer), message)
                .map_err(SourceError::Transport)?;
        }
        Ok(())
    }
}

/// Hash media bytes when the source has the full object available.
pub fn hash_media_bytes(bytes: &[u8]) -> [u8; 32] {
    hash_bytes(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::delivery::{AssetStreamHeader, MediaStreamHeader};
    use crate::time::IdentityGroupClock;
    use std::collections::{HashMap, VecDeque};
    use std::sync::{Arc, Mutex};

    struct RecordingWriter {
        store: Arc<Mutex<HashMap<String, Vec<u8>>>>,
        key: String,
    }
    impl Write for RecordingWriter {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.store
                .lock()
                .unwrap()
                .entry(self.key.clone())
                .or_default()
                .extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    #[derive(Default)]
    struct MockMedia {
        store: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    }
    impl MediaStreamTransport for MockMedia {
        type Error = ();
        type Writer = RecordingWriter;
        type Reader = io::Cursor<Vec<u8>>;
        fn open_media_writer(&self, route_key: &str) -> Result<Self::Writer, ()> {
            Ok(RecordingWriter {
                store: self.store.clone(),
                key: route_key.to_string(),
            })
        }
        fn open_media_reader(&self) -> Result<Self::Reader, ()> {
            Ok(io::Cursor::new(Vec::new()))
        }
    }

    #[derive(Default)]
    struct MockControl {
        sent: Arc<Mutex<Vec<(Option<String>, AudioControlMessage)>>>,
        inbox: Mutex<VecDeque<(String, AudioControlMessage)>>,
    }
    impl ControlTransport for MockControl {
        type Error = ();
        fn send_control(
            &self,
            participant_id: Option<&str>,
            message: &AudioControlMessage,
        ) -> Result<(), ()> {
            self.sent
                .lock()
                .unwrap()
                .push((participant_id.map(str::to_string), message.clone()));
            Ok(())
        }
        fn recv_control(&self) -> Result<(String, AudioControlMessage), ()> {
            self.inbox.lock().unwrap().pop_front().ok_or(())
        }
    }

    fn config(renderers: &[&str]) -> SourceSessionConfig {
        SourceSessionConfig {
            renderer_ids: renderers.iter().map(|s| (*s).to_string()).collect(),
            ..Default::default()
        }
    }

    fn engine(
        config: SourceSessionConfig,
    ) -> AudioSourceEngine<IdentityGroupClock, MockControl, MockMedia> {
        AudioSourceEngine::new(
            IdentityGroupClock::new(),
            MockControl::default(),
            MockMedia::default(),
            config,
        )
    }

    #[test]
    fn streaming_fan_out_writes_every_renderer_from_one_pass() {
        let engine = engine(config(&["a", "b"]));
        let store = engine.media.store.clone();
        let body = vec![42u8; STREAM_CHUNK_BYTES * 2 + 7]; // spans multiple chunks
        let plan = DeliveryPlan {
            policy: MediaDeliveryPolicy::PerPeerUnicast,
            media_stream_count: 2,
            estimated_source_uplink_bytes: 0,
            estimated_medium_bytes: 0,
            notes: "",
        };
        let written = engine
            .push_media(
                &plan,
                9,
                body.len() as u64,
                io::Cursor::new(body.clone()),
                &["a".into(), "b".into()],
            )
            .unwrap();
        assert_eq!(written, body.len() as u64);
        let store = store.lock().unwrap();
        for key in ["a", "b"] {
            let framed = &store[key];
            let header = MediaStreamHeader::from_bytes(framed).unwrap();
            assert_eq!(header.generation, 9);
            // MediaStreamHeader prefix, then the exact container bytes.
            assert_eq!(&framed[MediaStreamHeader::LEN..], &body[..]);
        }
    }

    #[test]
    fn single_device_fast_start_commits_without_waiting_for_ready() {
        // inbox is empty: if the fast path tried to drain Ready it would error.
        let engine = engine(config(&["solo"]));
        let sent = engine.control.sent.clone();
        engine.synchronize_start(3).unwrap();
        let sent = sent.lock().unwrap();
        assert!(sent.iter().any(|(_, m)| matches!(m, AudioControlMessage::PrepareSynced { .. })));
        assert!(sent.iter().any(|(_, m)| matches!(m, AudioControlMessage::Commit { generation: 3, .. })));
    }

    #[test]
    fn multi_renderer_still_runs_the_barrier() {
        let mut cfg = config(&["a", "b"]);
        cfg.min_buffer_ms = 10;
        let engine = engine(cfg);
        for id in ["a", "b"] {
            engine.control.inbox.lock().unwrap().push_back((
                id.into(),
                AudioControlMessage::Ready {
                    generation: 5,
                    participant_id: id.into(),
                    uncertainty_ns: 0,
                    buffered_ms: 100,
                },
            ));
        }
        engine.synchronize_start(5).unwrap();
        let sent = engine.control.sent.lock().unwrap();
        assert!(sent.iter().any(|(_, m)| matches!(m, AudioControlMessage::Commit { generation: 5, .. })));
    }

    #[test]
    fn transport_command_is_rebroadcast_to_all_renderers() {
        let engine = engine(config(&["a", "b"]));
        assert!(engine
            .apply_transport_command(&AudioControlMessage::Pause { generation: 1 })
            .unwrap());
        assert!(!engine
            .apply_transport_command(&AudioControlMessage::Ping)
            .unwrap());
        let sent = engine.control.sent.lock().unwrap();
        let pauses = sent
            .iter()
            .filter(|(_, m)| matches!(m, AudioControlMessage::Pause { .. }))
            .count();
        assert_eq!(pauses, 2, "one Pause re-sent per renderer");
    }

    #[test]
    fn serve_asset_stream_announces_on_control_and_frames_on_data_plane() {
        let engine = engine(config(&["r1"]));
        let bytes = vec![7u8; 2048];
        let hash = hash_bytes(&bytes);
        let blob = AssetBlob {
            mime: "image/png".into(),
            bytes: bytes.clone(),
        };
        let mut wire = Vec::new();
        engine
            .serve_asset_stream("r1", hash, &blob, &mut wire)
            .unwrap();
        // Control plane only carries the small announce, not the bytes.
        let sent = engine.control.sent.lock().unwrap();
        assert!(sent.iter().any(|(to, m)| to.as_deref() == Some("r1")
            && matches!(m, AudioControlMessage::AssetStreamAnnounce { byte_length, .. } if *byte_length == 2048)));
        // Data plane carries the framed asset.
        let header = AssetStreamHeader::from_prefix(&wire).unwrap();
        assert_eq!(header.hash, hash);
        assert_eq!(header.byte_length, 2048);
    }
}
