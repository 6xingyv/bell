//! Wire contract for `com.mocharealm.foundation.audio.render`.
//!
//! Control and asset transfer use reliable messages. Compressed media uses a
//! reliable stream. Playback feedback uses datagrams (latest-state, droppable).
//! Clock/barrier timing uses fabric SessionClock GroupInstant values; this
//! crate only carries those integers and never reimplements hub clock probing.

use serde::{Deserialize, Serialize};

/// Content-addressed blob id (SHA-256).
pub type ContentHash = [u8; 32];

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AudioFabricRole {
    Source,
    Renderer,
    Controller,
}

/// How compressed media bytes are delivered to renderers.
///
/// Logical topology is always one source port → many renderer ports. The
/// delivery policy only chooses the physical fanout strategy so Ability line
/// semantics stay stable when the hub enables relay or multicast.
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaDeliveryPolicy {
    /// One reliable stream per renderer (default). Highest uplink cost on the
    /// source: roughly `N * media_bytes` plus control/assets.
    #[default]
    PerPeerUnicast,
    /// Source sends once to a relay root; hub/peers re-forward opaque bytes.
    /// Source uplink ≈ `1 * media_bytes` when the tree is balanced.
    RelayTree,
    /// Single group delivery when the link supports multicast. Medium load ≈
    /// one copy; still requires group key / epoch / replay protection from fabric.
    Multicast,
}

/// Presentation + technical track metadata carried on the control plane.
///
/// Large blobs (cover art, long lyrics) are content-addressed: the announce
/// carries hashes, and peers request missing assets once. Do not embed multi-MB
/// cover art in every control message or re-send it on seek.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(default)]
pub struct TrackMetaWire {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub genre: Option<String>,
    pub year: Option<i32>,
    pub track_number: Option<i32>,
    pub disc_number: Option<i32>,
    pub duration_ms: u64,
    pub sample_rate: u32,
    pub channels: u16,
    pub bit_rate: u32,
    pub replay_gain_db: Option<f32>,
    /// Inline lyrics when small; otherwise set [`TrackAnnounce::lyrics_hash`].
    pub lyrics: Option<String>,
}

/// Exact demux + decode parameters carried on the control plane so the renderer
/// can start FFmpeg without blind stream analysis.
///
/// FFmpeg's default remote path reads up to `probesize` (32 MiB here) through
/// `avformat_find_stream_info` before it will hand out the first frame. On a
/// progressive network stream that stalls first audio for seconds. When the
/// source already knows the container and codec (it demuxed the local file to
/// announce the track), it ships these fields and the renderer configures the
/// demuxer + decoder directly — data in, sound out, no probe.
///
/// Numeric fields mirror `AVCodecParameters`. `codec_id` follows `AVCodecID`
/// within one FFmpeg major; `codec_name` is the portable fallback used when the
/// numeric id does not resolve on the renderer's build.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
pub struct AudioStreamParameters {
    /// FFmpeg demuxer short name, e.g. `"mp3"`, `"flac"`, `"ogg"`, `"aac"`,
    /// `"matroska,webm"`, `"mov,mp4,m4a,3gp,3g2,mj2"`. Skips format probing.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    /// `AVCodecID` numeric value of the audio stream.
    pub codec_id: u32,
    /// Portable codec name (`"flac"`, `"aac"`, `"opus"`, `"mp3"`, …). Fallback
    /// when `codec_id` does not resolve on the renderer's FFmpeg build.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub codec_name: Option<String>,
    pub sample_rate: u32,
    pub channels: u16,
    /// `AV_CH_LAYOUT_*` bitmask; `0` means "derive default from `channels`".
    pub channel_layout: u64,
    /// `AVSampleFormat` of the stream's native decode output (informational;
    /// the renderer always resamples to its device format).
    pub sample_format: i32,
    pub bit_rate: i64,
    pub bits_per_coded_sample: u32,
    pub block_align: u32,
    pub frame_size: u32,
    /// Codec extradata (FLAC `STREAMINFO`, AAC `AudioSpecificConfig`, Vorbis
    /// headers, …). Mandatory for codecs that cannot be decoded from packets
    /// alone; empty when the codec is self-describing.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub extradata: Vec<u8>,
    /// Stream time base numerator / denominator; `0/0` when unknown.
    pub time_base_num: u32,
    pub time_base_den: u32,
}

impl AudioStreamParameters {
    /// A parameter set is only trustworthy for zero-probe if it carries the
    /// fields FFmpeg needs to build a decoder without reading the container.
    #[must_use]
    pub fn is_decodable(&self) -> bool {
        self.codec_id != 0 && self.sample_rate > 0 && self.channels > 0
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct MediaDescriptor {
    /// Preferred: original compressed container (mp3/aac/flac/opus…), never PCM.
    pub container_hint: Option<String>,
    pub codec_hint: Option<String>,
    pub byte_length: Option<u64>,
    pub content_hash: Option<ContentHash>,
    #[serde(default)]
    pub delivery: MediaDeliveryPolicy,
    /// Exact decode parameters for zero-probe startup. `Some` lets the renderer
    /// bypass FFmpeg's `probesize` stream analysis; `None` falls back to probing.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub decode: Option<AudioStreamParameters>,
    /// Playhead handover: media position (ms) at which the renderer should start
    /// playback. `0` when the stream itself already begins at the playhead
    /// (e.g. a remuxed cast stream) or the track starts from the beginning.
    #[serde(default, skip_serializing_if = "is_zero_u64")]
    pub start_position_ms: u64,
}

fn is_zero_u64(value: &u64) -> bool {
    *value == 0
}

/// Base64 (standard alphabet, padded) for binary asset payloads on the JSON
/// control plane — 3/4 the size of a JSON number array and what platform
/// adapters historically shipped. Deserialization also accepts the legacy
/// number-array form.
mod b64 {
    use base64::Engine as _;
    use base64::engine::general_purpose::STANDARD;
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&STANDARD.encode(bytes))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Repr {
            Text(String),
            Raw(Vec<u8>),
        }
        match Repr::deserialize(deserializer)? {
            Repr::Text(text) => STANDARD.decode(text).map_err(serde::de::Error::custom),
            Repr::Raw(bytes) => Ok(bytes),
        }
    }
}

/// Control-plane messages for `com.mocharealm.foundation.audio.render`.
///
/// The `control` port is **bidirectional** reliable_messages. Source → renderer
/// carries announce / assets / barrier; renderer/controller → source carries
/// [`AssetRequest`](Self::AssetRequest), barrier [`Ready`](Self::Ready) /
/// [`Reject`](Self::Reject), and the transport commands
/// [`Play`](Self::Play) / [`Pause`](Self::Pause) / [`Stop`](Self::Stop) /
/// [`Seek`](Self::Seek). fabric-router routes the reverse direction back to the
/// source participant so a renderer can drive playback (see task 3).
///
/// Large binary assets (cover art) do **not** ride this plane: they use
/// [`AssetStreamAnnounce`](Self::AssetStreamAnnounce) + a unidirectional data
/// stream, or an out-of-band [`TrackAnnounce::cover_url`], to keep transport
/// commands from queueing behind multi-hundred-KB cover chunks (task 2).
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AudioControlMessage {
    /// Source → renderers: new logical track. Assets referenced by hash.
    TrackAnnounce {
        generation: u64,
        meta: TrackMetaWire,
        cover_hash: Option<ContentHash>,
        /// When lyrics are large or shared across tracks.
        lyrics_hash: Option<ContentHash>,
        /// Out-of-band cover source (HTTP/CDN). When set the renderer may fetch
        /// artwork asynchronously off-fabric and never request the bytes here.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        cover_url: Option<String>,
        media: MediaDescriptor,
    },
    /// Renderer → source/coordinator: missing content-addressed blob.
    AssetRequest {
        hash: ContentHash,
    },
    /// Source → renderer: full asset when small enough for one message.
    /// `bytes` rides as base64 text on the JSON control plane (`bytes_b64`
    /// accepted as a legacy alias).
    AssetOffer {
        hash: ContentHash,
        mime: String,
        #[serde(with = "b64", alias = "bytes_b64")]
        bytes: Vec<u8>,
    },
    /// Source → renderer: chunked asset transfer for large covers/lyrics.
    ///
    /// Legacy control-plane fallback for links without a spare unidirectional
    /// stream. Prefer [`AssetStreamAnnounce`](Self::AssetStreamAnnounce).
    AssetChunk {
        hash: ContentHash,
        mime: String,
        index: u32,
        total: u32,
        #[serde(with = "b64", alias = "data_b64")]
        data: Vec<u8>,
    },
    /// Source → renderer: the asset `hash` is being pushed on a dedicated
    /// unidirectional data-plane stream (fabric `open_uni`), not inline here.
    /// Removes head-of-line blocking of transport commands behind cover bytes.
    AssetStreamAnnounce {
        hash: ContentHash,
        mime: String,
        byte_length: u64,
    },
    /// Barrier-style prepare using fabric GroupInstant nanoseconds.
    /// Renderers map via SessionClock::group_to_local when available.
    ///
    /// `start_at_local_ns` is an optional platform bridge field: when the Hub
    /// path has no SessionClock, the source may pre-map GroupInstant → remote
    /// local monotonic time after application-level clock probes.
    PrepareSynced {
        generation: u64,
        activate_at_group_ns: i128,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        start_at_local_ns: Option<u64>,
    },
    /// Renderer acknowledges it has enough media buffer and clock quality.
    Ready {
        generation: u64,
        participant_id: String,
        uncertainty_ns: u64,
        buffered_ms: u32,
    },
    Reject {
        generation: u64,
        participant_id: String,
        reason: String,
    },
    Commit {
        generation: u64,
        activate_at_group_ns: i128,
    },
    Abort {
        generation: u64,
        reason: String,
    },
    /// Transport commands: `generation` defaults to `0` ("the current track")
    /// so lightweight controllers that do not track generations stay valid.
    Play {
        #[serde(default)]
        generation: u64,
    },
    Pause {
        #[serde(default)]
        generation: u64,
    },
    Stop {
        #[serde(default)]
        generation: u64,
    },
    Seek {
        #[serde(default)]
        generation: u64,
        position_ms: u64,
    },
    /// Lightweight local control used by platform adapters without a full session.
    Ping,
    Status,
}

/// Droppable latest-state feedback on the `feedback` datagram port.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct AudioFeedbackDatagram {
    pub generation: u64,
    pub state: AudioPlaybackState,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub buffered_ms: u32,
    pub sample_rate: u32,
    pub underruns: u32,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AudioPlaybackState {
    Stopped,
    Playing,
    Paused,
    Buffering,
}

/// Maximum inline asset size on reliable messages before chunking.
pub const MAX_INLINE_ASSET_BYTES: usize = 48 * 1024;
/// Asset chunk payload size for control-plane transfer.
pub const ASSET_CHUNK_BYTES: usize = 24 * 1024;
/// Lyrics larger than this should be content-addressed rather than inlined.
pub const MAX_INLINE_LYRICS_CHARS: usize = 8 * 1024;
/// Upper bound for one framed asset on a data-plane stream (cover art/lyrics).
/// The announced `byte_length` is peer-controlled; reject anything above this
/// before allocating so a hostile header cannot force a huge allocation.
pub const MAX_STREAMED_ASSET_BYTES: usize = 16 * 1024 * 1024;

impl TrackMetaWire {
    pub fn from_parts(
        title: Option<String>,
        artist: Option<String>,
        album: Option<String>,
        album_artist: Option<String>,
        genre: Option<String>,
        year: Option<i32>,
        track_number: Option<i32>,
        disc_number: Option<i32>,
        duration_ms: u64,
        sample_rate: u32,
        channels: u16,
        bit_rate: u32,
        replay_gain_db: Option<f32>,
        lyrics: Option<String>,
    ) -> Self {
        Self {
            title,
            artist,
            album,
            album_artist,
            genre,
            year,
            track_number,
            disc_number,
            duration_ms,
            sample_rate,
            channels,
            bit_rate,
            replay_gain_db,
            lyrics,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn control_message_round_trip_preserves_metadata() {
        let msg = AudioControlMessage::TrackAnnounce {
            generation: 3,
            meta: TrackMetaWire {
                title: Some("Song".into()),
                artist: Some("Artist".into()),
                album: Some("Album".into()),
                lyrics: Some("la la".into()),
                duration_ms: 180_000,
                sample_rate: 48_000,
                channels: 2,
                ..Default::default()
            },
            cover_hash: Some([9u8; 32]),
            lyrics_hash: None,
            cover_url: Some("https://cdn.example/cover.jpg".into()),
            media: MediaDescriptor {
                container_hint: Some("mp3".into()),
                codec_hint: Some("mp3".into()),
                byte_length: Some(1_024_000),
                content_hash: Some([1u8; 32]),
                delivery: MediaDeliveryPolicy::RelayTree,
                decode: Some(AudioStreamParameters {
                    container: Some("mp3".into()),
                    codec_id: 86_017,
                    codec_name: Some("mp3".into()),
                    sample_rate: 44_100,
                    channels: 2,
                    channel_layout: 3,
                    bit_rate: 320_000,
                    extradata: vec![1, 2, 3, 4],
                    ..Default::default()
                }),
                start_position_ms: 0,
            },
        };
        let bytes = serde_json::to_vec(&msg).unwrap();
        let back: AudioControlMessage = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(back, msg);
    }

    #[test]
    fn descriptor_without_decode_params_is_omitted_and_defaults_to_probing() {
        let media = MediaDescriptor {
            container_hint: None,
            codec_hint: None,
            byte_length: None,
            content_hash: None,
            delivery: MediaDeliveryPolicy::PerPeerUnicast,
            decode: None,
            start_position_ms: 0,
        };
        let json = serde_json::to_string(&media).unwrap();
        assert!(!json.contains("decode"), "decode must be omitted when absent");
        let back: MediaDescriptor = serde_json::from_str(&json).unwrap();
        assert_eq!(back.decode, None);
    }

    #[test]
    fn decode_params_gate_zero_probe() {
        let mut params = AudioStreamParameters {
            codec_id: 86_017,
            sample_rate: 44_100,
            channels: 2,
            ..Default::default()
        };
        assert!(params.is_decodable());
        params.sample_rate = 0;
        assert!(!params.is_decodable());
    }

    #[test]
    fn asset_stream_announce_round_trips() {
        let msg = AudioControlMessage::AssetStreamAnnounce {
            hash: [5u8; 32],
            mime: "image/jpeg".into(),
            byte_length: 512_000,
        };
        let bytes = serde_json::to_vec(&msg).unwrap();
        assert_eq!(serde_json::from_slice::<AudioControlMessage>(&bytes).unwrap(), msg);
    }
}
