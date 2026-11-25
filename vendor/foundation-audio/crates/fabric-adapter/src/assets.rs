//! Content-addressed cover art / lyrics transfer helpers.
//!
//! Large presentation assets travel on the control plane by hash, not inside
//! every media stream. Renderers that already cache a hash skip the transfer.

use crate::protocol::{
    ASSET_CHUNK_BYTES, AudioControlMessage, ContentHash, MAX_INLINE_ASSET_BYTES,
    MAX_INLINE_LYRICS_CHARS, TrackMetaWire,
};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssetBlob {
    pub mime: String,
    pub bytes: Vec<u8>,
}

#[derive(Default)]
pub struct AssetCache {
    blobs: HashMap<ContentHash, AssetBlob>,
}

impl AssetCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&mut self, hash: ContentHash, blob: AssetBlob) {
        self.blobs.insert(hash, blob);
    }

    pub fn get(&self, hash: &ContentHash) -> Option<&AssetBlob> {
        self.blobs.get(hash)
    }

    pub fn contains(&self, hash: &ContentHash) -> bool {
        self.blobs.contains_key(hash)
    }

    pub fn put_bytes(&mut self, mime: impl Into<String>, bytes: Vec<u8>) -> ContentHash {
        let hash = hash_bytes(&bytes);
        self.blobs.insert(
            hash,
            AssetBlob {
                mime: mime.into(),
                bytes,
            },
        );
        hash
    }
}

pub fn hash_bytes(bytes: &[u8]) -> ContentHash {
    let digest = Sha256::digest(bytes);
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    out
}

/// Build announce fields for cover/lyrics without embedding large payloads.
pub struct AnnouncedAssets {
    pub meta: TrackMetaWire,
    pub cover_hash: Option<ContentHash>,
    pub lyrics_hash: Option<ContentHash>,
    pub cover: Option<AssetBlob>,
    pub lyrics_asset: Option<AssetBlob>,
}

pub fn prepare_announce_assets(
    mut meta: TrackMetaWire,
    cover: Option<Vec<u8>>,
    cover_mime: &str,
    lyrics: Option<String>,
) -> AnnouncedAssets {
    let cover = cover.map(|bytes| AssetBlob {
        mime: cover_mime.to_owned(),
        bytes,
    });
    let cover_hash = cover.as_ref().map(|blob| hash_bytes(&blob.bytes));

    let (lyrics_inline, lyrics_asset, lyrics_hash) = match lyrics {
        Some(text) if text.chars().count() > MAX_INLINE_LYRICS_CHARS => {
            let bytes = text.into_bytes();
            let hash = hash_bytes(&bytes);
            (
                None,
                Some(AssetBlob {
                    mime: "text/plain; charset=utf-8".into(),
                    bytes,
                }),
                Some(hash),
            )
        }
        other => {
            meta.lyrics = other;
            (meta.lyrics.clone(), None, None)
        }
    };
    let _ = lyrics_inline;
    AnnouncedAssets {
        meta,
        cover_hash,
        lyrics_hash,
        cover,
        lyrics_asset,
    }
}

/// Encode an asset as one offer or a sequence of chunks.
pub fn asset_transfer_messages(hash: ContentHash, blob: &AssetBlob) -> Vec<AudioControlMessage> {
    if blob.bytes.len() <= MAX_INLINE_ASSET_BYTES {
        return vec![AudioControlMessage::AssetOffer {
            hash,
            mime: blob.mime.clone(),
            bytes: blob.bytes.clone(),
        }];
    }
    let total = blob.bytes.len().div_ceil(ASSET_CHUNK_BYTES) as u32;
    blob.bytes
        .chunks(ASSET_CHUNK_BYTES)
        .enumerate()
        .map(|(index, chunk)| AudioControlMessage::AssetChunk {
            hash,
            mime: blob.mime.clone(),
            index: index as u32,
            total,
            data: chunk.to_vec(),
        })
        .collect()
}

/// Reassemble chunked assets on the renderer.
#[derive(Default)]
pub struct AssetAssembler {
    partial: HashMap<ContentHash, PartialAsset>,
}

struct PartialAsset {
    mime: String,
    total: u32,
    parts: HashMap<u32, Vec<u8>>,
}

impl AssetAssembler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn accept_offer(&mut self, _hash: ContentHash, mime: String, bytes: Vec<u8>) -> AssetBlob {
        AssetBlob { mime, bytes }
    }

    pub fn accept_chunk(
        &mut self,
        hash: ContentHash,
        mime: String,
        index: u32,
        total: u32,
        data: Vec<u8>,
    ) -> Option<AssetBlob> {
        let entry = self.partial.entry(hash).or_insert_with(|| PartialAsset {
            mime,
            total,
            parts: HashMap::new(),
        });
        entry.parts.insert(index, data);
        if entry.parts.len() as u32 != entry.total {
            return None;
        }
        let mut ordered = Vec::new();
        for i in 0..entry.total {
            ordered.extend(entry.parts.remove(&i)?);
        }
        let mime = entry.mime.clone();
        self.partial.remove(&hash);
        let actual = hash_bytes(&ordered);
        if actual != hash {
            return None;
        }
        Some(AssetBlob {
            mime,
            bytes: ordered,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn large_asset_is_chunked_and_reassembled() {
        let bytes = vec![7u8; MAX_INLINE_ASSET_BYTES + 100];
        let hash = hash_bytes(&bytes);
        let blob = AssetBlob {
            mime: "image/jpeg".into(),
            bytes: bytes.clone(),
        };
        let messages = asset_transfer_messages(hash, &blob);
        assert!(messages.len() > 1);
        let mut assembler = AssetAssembler::new();
        let mut done = None;
        for message in messages {
            if let AudioControlMessage::AssetChunk {
                hash,
                mime,
                index,
                total,
                data,
            } = message
            {
                done = assembler.accept_chunk(hash, mime, index, total, data);
            }
        }
        assert_eq!(done.unwrap().bytes, bytes);
    }

    #[test]
    fn long_lyrics_become_content_addressed() {
        let lyrics = "x".repeat(MAX_INLINE_LYRICS_CHARS + 10);
        let announced = prepare_announce_assets(
            TrackMetaWire {
                title: Some("t".into()),
                ..Default::default()
            },
            None,
            "image/jpeg",
            Some(lyrics.clone()),
        );
        assert!(announced.meta.lyrics.is_none());
        assert!(announced.lyrics_hash.is_some());
        assert_eq!(
            String::from_utf8(announced.lyrics_asset.unwrap().bytes).unwrap(),
            lyrics
        );
    }
}
