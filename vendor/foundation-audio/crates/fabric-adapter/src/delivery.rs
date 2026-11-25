//! Network delivery planning for one-source-to-many audio.
//!
//! Goals:
//! 1. Never fan out PCM — only compressed containers / segments.
//! 2. Send presentation assets by content hash; peers that already have the
//!    hash pull nothing.
//! 3. Choose a physical fanout that matches fabric route policy so multi-device
//!    play does not multiply source uplink without need.
//! 4. Keep media off the control/datagram paths; feedback stays latest-state.

use crate::protocol::{ContentHash, MediaDeliveryPolicy};

/// Planning inputs known before opening media streams.
#[derive(Clone, Debug)]
pub struct DeliveryPlanInput {
    pub renderer_count: usize,
    pub media_bytes: Option<u64>,
    pub cover_bytes: Option<u64>,
    pub lyrics_bytes: Option<u64>,
    /// How many peers already cache cover/lyrics hashes.
    pub cover_cache_hits: usize,
    pub lyrics_cache_hits: usize,
    /// Session/hub capability flags from fabric plan negotiation.
    pub allow_relay: bool,
    pub allow_multicast: bool,
    /// Preferred policy from ability opaque config; may be upgraded/downgraded.
    pub preferred: MediaDeliveryPolicy,
}

/// Concrete plan emitted to the source orchestrator.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeliveryPlan {
    pub policy: MediaDeliveryPolicy,
    /// How many media reliable streams the source should open.
    pub media_stream_count: usize,
    /// Estimated bytes leaving the source uplink for this generation.
    pub estimated_source_uplink_bytes: u64,
    /// Estimated total bytes on the shared wireless medium (rough).
    pub estimated_medium_bytes: u64,
    pub notes: &'static str,
}

/// Pick a delivery policy and estimate traffic.
///
/// Rough model (control messages ignored as negligible):
/// - Unicast: source uplink = N × media + assets × misses
/// - Relay:   source uplink ≈ 1 × media + assets; medium ≈ (N-1)×media for pure
///   unicast re-forward, better with hierarchy
/// - Multicast: source and medium ≈ 1 × media (+ assets as unicast repair)
pub fn plan_delivery(input: &DeliveryPlanInput) -> DeliveryPlan {
    let n = input.renderer_count.max(1);
    let media = input.media_bytes.unwrap_or(0);
    let cover = asset_transfer_bytes(input.cover_bytes, input.cover_cache_hits, n);
    let lyrics = asset_transfer_bytes(input.lyrics_bytes, input.lyrics_cache_hits, n);
    let assets = cover.saturating_add(lyrics);

    let policy = select_policy(input);
    match policy {
        MediaDeliveryPolicy::PerPeerUnicast => DeliveryPlan {
            policy,
            media_stream_count: n,
            estimated_source_uplink_bytes: media.saturating_mul(n as u64).saturating_add(assets),
            estimated_medium_bytes: media.saturating_mul(n as u64).saturating_add(assets),
            notes: "per-peer unicast: independent congestion and E2EE; highest source uplink",
        },
        MediaDeliveryPolicy::RelayTree => DeliveryPlan {
            policy,
            media_stream_count: 1,
            estimated_source_uplink_bytes: media.saturating_add(assets),
            // Ideal balanced tree ≈ ~media on each hop layer; upper-bound N-1 hops of media.
            estimated_medium_bytes: media
                .saturating_mul(n.saturating_sub(1) as u64)
                .saturating_add(assets),
            notes: "relay tree: source sends once; hub/peers re-forward opaque bytes",
        },
        MediaDeliveryPolicy::Multicast => DeliveryPlan {
            policy,
            media_stream_count: 1,
            estimated_source_uplink_bytes: media.saturating_add(assets),
            estimated_medium_bytes: media.saturating_add(assets),
            notes: "multicast: single medium copy when link supports it; unicast NACK/repair separate",
        },
    }
}

fn select_policy(input: &DeliveryPlanInput) -> MediaDeliveryPolicy {
    match input.preferred {
        MediaDeliveryPolicy::Multicast if input.allow_multicast => MediaDeliveryPolicy::Multicast,
        MediaDeliveryPolicy::Multicast if input.allow_relay => MediaDeliveryPolicy::RelayTree,
        MediaDeliveryPolicy::Multicast => MediaDeliveryPolicy::PerPeerUnicast,
        MediaDeliveryPolicy::RelayTree if input.allow_relay || input.allow_multicast => {
            if input.allow_multicast && input.renderer_count >= 3 {
                MediaDeliveryPolicy::Multicast
            } else if input.allow_relay {
                MediaDeliveryPolicy::RelayTree
            } else {
                MediaDeliveryPolicy::PerPeerUnicast
            }
        }
        MediaDeliveryPolicy::RelayTree => MediaDeliveryPolicy::PerPeerUnicast,
        MediaDeliveryPolicy::PerPeerUnicast => {
            // Auto-upgrade when many renderers and the session allows cheaper fanout.
            if input.renderer_count >= 3 && input.allow_multicast {
                MediaDeliveryPolicy::Multicast
            } else if input.renderer_count >= 3 && input.allow_relay {
                MediaDeliveryPolicy::RelayTree
            } else {
                MediaDeliveryPolicy::PerPeerUnicast
            }
        }
    }
}

fn asset_transfer_bytes(size: Option<u64>, cache_hits: usize, renderer_count: usize) -> u64 {
    let Some(size) = size else {
        return 0;
    };
    let misses = renderer_count.saturating_sub(cache_hits.min(renderer_count)) as u64;
    size.saturating_mul(misses)
}

/// Media stream framing: generation-scoped progressive container bytes.
///
/// Keep framing thin so FFmpeg on the renderer can demux the stream directly
/// after optional header strip. Flags reserved for future segment/ABR modes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MediaStreamHeader {
    pub generation: u64,
    pub flags: u32,
    pub byte_length: u64,
    /// Media-timeline position (ms) at which byte 0 of this stream begins.
    /// `0` for a stream that starts at the top of the track. A seek on a
    /// progressive source restarts delivery on a fresh stream whose header
    /// carries the new position — the renderer re-attaches there and the old
    /// stream (including in-flight bytes) is discarded.
    pub start_position_ms: u64,
}

impl MediaStreamHeader {
    pub const FLAG_COMPRESSED_CONTAINER: u32 = 1;
    pub const FLAG_END_OF_OBJECT: u32 = 1 << 1;

    /// Serialized size (generation · flags · byte_length · start_position_ms).
    pub const LEN: usize = 8 + 4 + 8 + 8;

    pub fn to_bytes(self) -> [u8; Self::LEN] {
        let mut out = [0u8; Self::LEN];
        out[0..8].copy_from_slice(&self.generation.to_be_bytes());
        out[8..12].copy_from_slice(&self.flags.to_be_bytes());
        out[12..20].copy_from_slice(&self.byte_length.to_be_bytes());
        out[20..28].copy_from_slice(&self.start_position_ms.to_be_bytes());
        out
    }

    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < Self::LEN {
            return None;
        }
        Some(Self {
            generation: u64::from_be_bytes(bytes[0..8].try_into().ok()?),
            flags: u32::from_be_bytes(bytes[8..12].try_into().ok()?),
            byte_length: u64::from_be_bytes(bytes[12..20].try_into().ok()?),
            start_position_ms: u64::from_be_bytes(bytes[20..28].try_into().ok()?),
        })
    }
}

/// Framing for one content-addressed asset (cover art, long lyrics) delivered on
/// a dedicated unidirectional data-plane stream (task 2), off the control plane.
///
/// Layout: 32-byte `hash` · 2-byte big-endian `mime_len` · 8-byte big-endian
/// `byte_length`, then `mime_len` UTF-8 mime bytes, then `byte_length` payload
/// bytes. The renderer verifies the hash after reassembly.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AssetStreamHeader {
    pub hash: ContentHash,
    pub mime_len: u16,
    pub byte_length: u64,
}

impl AssetStreamHeader {
    /// Fixed-size prefix length (hash + mime_len + byte_length).
    pub const PREFIX_LEN: usize = 32 + 2 + 8;

    pub fn to_prefix(&self) -> [u8; Self::PREFIX_LEN] {
        let mut out = [0u8; Self::PREFIX_LEN];
        out[0..32].copy_from_slice(&self.hash);
        out[32..34].copy_from_slice(&self.mime_len.to_be_bytes());
        out[34..42].copy_from_slice(&self.byte_length.to_be_bytes());
        out
    }

    pub fn from_prefix(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < Self::PREFIX_LEN {
            return None;
        }
        let mut hash = [0u8; 32];
        hash.copy_from_slice(&bytes[0..32]);
        Some(Self {
            hash,
            mime_len: u16::from_be_bytes(bytes[32..34].try_into().ok()?),
            byte_length: u64::from_be_bytes(bytes[34..42].try_into().ok()?),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unicast_multiplies_media_by_renderer_count() {
        let plan = plan_delivery(&DeliveryPlanInput {
            renderer_count: 4,
            media_bytes: Some(1_000_000),
            cover_bytes: Some(100_000),
            lyrics_bytes: None,
            cover_cache_hits: 1,
            lyrics_cache_hits: 0,
            allow_relay: false,
            allow_multicast: false,
            preferred: MediaDeliveryPolicy::PerPeerUnicast,
        });
        assert_eq!(plan.media_stream_count, 4);
        // 4 * media + 3 * cover (one cache hit)
        assert_eq!(plan.estimated_source_uplink_bytes, 4_000_000 + 300_000);
    }

    #[test]
    fn multicast_preferred_when_allowed_for_many_peers() {
        let plan = plan_delivery(&DeliveryPlanInput {
            renderer_count: 5,
            media_bytes: Some(2_000_000),
            cover_bytes: None,
            lyrics_bytes: None,
            cover_cache_hits: 0,
            lyrics_cache_hits: 0,
            allow_relay: true,
            allow_multicast: true,
            preferred: MediaDeliveryPolicy::PerPeerUnicast,
        });
        assert_eq!(plan.policy, MediaDeliveryPolicy::Multicast);
        assert_eq!(plan.media_stream_count, 1);
        assert_eq!(plan.estimated_source_uplink_bytes, 2_000_000);
    }

    #[test]
    fn media_header_round_trip() {
        let header = MediaStreamHeader {
            generation: 42,
            flags: MediaStreamHeader::FLAG_COMPRESSED_CONTAINER,
            byte_length: 99,
            start_position_ms: 123_456,
        };
        assert_eq!(MediaStreamHeader::from_bytes(&header.to_bytes()), Some(header));
    }

    #[test]
    fn asset_stream_header_round_trip() {
        let header = AssetStreamHeader {
            hash: [4u8; 32],
            mime_len: 10,
            byte_length: 512_000,
        };
        assert_eq!(AssetStreamHeader::from_prefix(&header.to_prefix()), Some(header));
        assert!(AssetStreamHeader::from_prefix(&[0u8; 8]).is_none());
    }
}
