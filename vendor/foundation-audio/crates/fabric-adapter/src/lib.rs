//! Device Fabric ↔ foundation.audio boundary for cross-device render.
//!
//! # Layering (see fabric `docs/11-reference-abilities.md`)
//!
//! ```text
//! hub                         — no dependency on foundation.audio
//! ability protocol engine     — this crate's source/renderer engines + transports
//!                               uses fabric SessionClock / Barrier / ports via traits
//! foundation.audio adapter    — LocalAudioRenderAdapter → PlaybackEngine
//! ```
//!
//! # Ports
//!
//! - `control`  reliable messages — metadata, assets by hash, barrier, transport control
//! - `media`    reliable stream   — compressed container progressive body (no fd)
//! - `feedback` datagram          — latest playback state (droppable)
//!
//! # What stays out of this crate
//!
//! - Hub discovery, identity, QUIC, policy
//! - Re-implementing fabric clock probing (use SessionClock)
//! - PCM fanout (always send compressed media)
//! - Hardware output latency compensation beyond local engine behavior

mod assets;
mod delivery;
mod protocol;
mod renderer;
mod source;
mod time;
mod transport;

#[cfg(feature = "sdk-bridge")]
pub mod sdk_bridge;
#[cfg(feature = "client")]
pub mod client;

pub use assets::{
    AssetAssembler, AssetBlob, AssetCache, asset_transfer_messages, hash_bytes,
    prepare_announce_assets,
};
pub use delivery::{
    AssetStreamHeader, DeliveryPlan, DeliveryPlanInput, MediaStreamHeader, plan_delivery,
};
pub use protocol::{
    ASSET_CHUNK_BYTES, AudioControlMessage, AudioFabricRole, AudioFeedbackDatagram,
    AudioPlaybackState, AudioStreamParameters, ContentHash, MAX_INLINE_ASSET_BYTES,
    MAX_INLINE_LYRICS_CHARS, MediaDeliveryPolicy, MediaDescriptor, TrackMetaWire,
};
pub use renderer::{AudioRendererEngine, StreamMediaSource};
pub use source::{
    AudioSourceEngine, SourceError, SourceSessionConfig, TrackOffer, hash_media_bytes,
};
pub use time::{
    GroupClock, GroupInstantNs, IdentityGroupClock, LocalInstantNs, OffsetGroupClock,
    ProcessMonotonicClock, TimeMapError,
};
pub use transport::{
    AssetStreamTransport, ControlTransport, FeedbackTransport, MediaStreamBody,
    MediaStreamTransport, read_asset_stream_object, write_asset_stream_object, write_media_object,
};

#[cfg(feature = "engine-adapter")]
pub use renderer::{LocalAudioRenderAdapter, SimpleInvokeResponse};

/// Ability identity constants shared with platform registrars.
pub const AUDIO_RENDER_ABILITY: &str = "com.mocharealm.foundation.audio.render";
pub const AUDIO_RENDER_MAJOR_VERSION: u16 = 1;
pub const CONTROL_PORT: &str = "control";
pub const MEDIA_STREAM: &str = "media";
/// Named broker stream carrying one framed cover/lyrics asset object.
pub const ASSET_STREAM: &str = "asset";
pub const FEEDBACK_PORT: &str = "feedback";

/// Clock requirement for the ability contract (`max_uncertainty_ns`).
pub const AUDIO_CLOCK_MAX_UNCERTAINTY_NS: u64 = 5_000_000;
