use ringbuf::traits::Split;
use ringbuf::{HeapCons, HeapProd, HeapRb};

#[derive(Debug, Clone)]
pub enum QueueItem {
    Path(String),
    Fd(i32),
}

impl QueueItem {
    pub fn is_path(&self) -> bool {
        matches!(self, QueueItem::Path(_))
    }

    pub fn is_fd(&self) -> bool {
        matches!(self, QueueItem::Fd(_))
    }

    pub fn as_path(&self) -> Option<&str> {
        if let QueueItem::Path(path) = self {
            Some(path)
        } else {
            None
        }
    }

    pub fn as_fd(&self) -> Option<i32> {
        if let QueueItem::Fd(fd) = self {
            Some(*fd)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct TrackMetadata {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub genre: Option<String>,
    pub year: Option<i32>,
    pub duration_ms: u64,
    pub sample_rate: u32,
    pub channels: u16,
    pub replay_gain_db: Option<f32>,
    pub cover_art: Option<Vec<u8>>,
    pub lyrics: Option<String>,
}

pub struct AudioTrack {
    pub metadata: TrackMetadata,
    pub pcm_producer: HeapProd<f32>,
    pub pcm_consumer: HeapCons<f32>,
}

impl AudioTrack {
    pub fn new(capacity: usize, metadata: TrackMetadata) -> Self {
        let rb = HeapRb::<f32>::new(capacity);
        let (prod, cons) = rb.split();
        Self {
            metadata,
            pcm_producer: prod,
            pcm_consumer: cons,
        }
    }
}

