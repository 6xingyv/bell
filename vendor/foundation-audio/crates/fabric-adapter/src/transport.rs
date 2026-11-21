//! Platform transport surface used by the audio ability protocol engine.
//!
//! Implementations bind to fabric-sdk (`MessagePort`, `StreamPort`,
//! `DatagramPort`) or to a test harness. This crate never talks to the Hub
//! directly and never takes media fds.

use crate::protocol::{AudioControlMessage, AudioFeedbackDatagram};
use std::io::{self, Read, Write};

/// Reliable control channel (fabric `control` port, reliable_messages).
pub trait ControlTransport {
    type Error;

    fn send_control(
        &self,
        participant_id: Option<&str>,
        message: &AudioControlMessage,
    ) -> Result<(), Self::Error>;

    fn recv_control(&self) -> Result<(String, AudioControlMessage), Self::Error>;
}

/// Source → renderer compressed media (fabric `media` reliable_stream).
///
/// Writers emit a [`crate::delivery::MediaStreamHeader`] followed by container
/// bytes. Readers strip the header and hand the remainder to stream decode.
pub trait MediaStreamTransport {
    type Error;
    type Writer: Write + Send;
    type Reader: Read + Send;

    /// Open outbound media toward one logical destination set.
    ///
    /// Under `PerPeerUnicast` the source opens one writer per renderer.
    /// Under `RelayTree` / `Multicast` the source opens a single writer and the
    /// hub/router fans out according to the session plan.
    fn open_media_writer(&self, route_key: &str) -> Result<Self::Writer, Self::Error>;

    fn open_media_reader(&self) -> Result<Self::Reader, Self::Error>;
}

/// Renderer → coordinator latest-state feedback (fabric `feedback` datagram).
pub trait FeedbackTransport {
    type Error;

    fn send_feedback(&self, feedback: &AudioFeedbackDatagram) -> Result<(), Self::Error>;

    fn try_recv_feedback(&self) -> Result<Option<(String, AudioFeedbackDatagram)>, Self::Error>;
}

/// Dedicated unidirectional data-plane transport for content-addressed assets
/// (cover art, long lyrics) — task 2. Maps to fabric `open_uni` / `accept_uni`.
///
/// Keeping large binary assets on their own stream stops them from queueing
/// behind — or being queued behind by — small control messages, so transport
/// commands (`Play`/`Pause`/`Seek`) stay low-latency while a cover transfers.
pub trait AssetStreamTransport {
    type Error;
    type Writer: Write + Send;
    type Reader: Read + Send;

    /// Open an outbound asset stream toward `participant_id` (a fresh uni stream).
    fn open_asset_writer(&self, participant_id: &str) -> Result<Self::Writer, Self::Error>;

    /// Accept the next inbound asset stream (renderer side).
    fn accept_asset_reader(&self) -> Result<Self::Reader, Self::Error>;
}

/// Convenience: write header + body to a media stream. `start_position_ms` is
/// the media-timeline position at which the body's byte 0 begins (`0` for the
/// top of the track; a seek restart carries the new position).
pub fn write_media_object<W: Write>(
    writer: &mut W,
    generation: u64,
    byte_length: u64,
    start_position_ms: u64,
    body: &mut dyn Read,
) -> io::Result<u64> {
    use crate::delivery::MediaStreamHeader;
    let header = MediaStreamHeader {
        generation,
        flags: MediaStreamHeader::FLAG_COMPRESSED_CONTAINER,
        byte_length,
        start_position_ms,
    };
    writer.write_all(&header.to_bytes())?;
    let copied = io::copy(body, writer)?;
    writer.flush()?;
    Ok(copied)
}

/// Read header then return a reader limited to the object body when length is known.
pub struct MediaStreamBody<R: Read> {
    pub generation: u64,
    pub byte_length: u64,
    /// Media-timeline position (ms) at which this stream's byte 0 begins.
    pub start_position_ms: u64,
    inner: R,
    remaining: Option<u64>,
}

impl<R: Read> MediaStreamBody<R> {
    pub fn open(mut reader: R) -> io::Result<Self> {
        use crate::delivery::MediaStreamHeader;
        let mut header_buf = [0u8; MediaStreamHeader::LEN];
        reader.read_exact(&mut header_buf)?;
        let header = MediaStreamHeader::from_bytes(&header_buf)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "media header"))?;
        let remaining = (header.byte_length > 0).then_some(header.byte_length);
        Ok(Self {
            generation: header.generation,
            byte_length: header.byte_length,
            start_position_ms: header.start_position_ms,
            inner: reader,
            remaining,
        })
    }
}

impl<R: Read> Read for MediaStreamBody<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if let Some(left) = self.remaining {
            if left == 0 {
                return Ok(0);
            }
            let max = usize::try_from(left).unwrap_or(buf.len()).min(buf.len());
            let n = self.inner.read(&mut buf[..max])?;
            self.remaining = Some(left.saturating_sub(n as u64));
            Ok(n)
        } else {
            self.inner.read(buf)
        }
    }
}

/// Write one framed asset (task 2) onto a unidirectional data-plane stream:
/// [`AssetStreamHeader`](crate::delivery::AssetStreamHeader) prefix · mime · payload.
pub fn write_asset_stream_object<W: Write>(
    writer: &mut W,
    hash: [u8; 32],
    mime: &str,
    payload: &[u8],
) -> io::Result<()> {
    use crate::delivery::AssetStreamHeader;
    let mime_bytes = mime.as_bytes();
    let mime_len = u16::try_from(mime_bytes.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "asset mime too long"))?;
    let header = AssetStreamHeader {
        hash,
        mime_len,
        byte_length: payload.len() as u64,
    };
    writer.write_all(&header.to_prefix())?;
    writer.write_all(mime_bytes)?;
    writer.write_all(payload)?;
    writer.flush()?;
    Ok(())
}

/// Read one framed asset from a unidirectional data-plane stream. Returns the
/// content hash, mime and payload. The caller verifies the hash before install.
pub fn read_asset_stream_object<R: Read>(reader: &mut R) -> io::Result<([u8; 32], String, Vec<u8>)> {
    use crate::delivery::AssetStreamHeader;
    let mut prefix = [0u8; AssetStreamHeader::PREFIX_LEN];
    reader.read_exact(&mut prefix)?;
    let header = AssetStreamHeader::from_prefix(&prefix)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "asset header"))?;
    let mut mime_bytes = vec![0u8; header.mime_len as usize];
    reader.read_exact(&mut mime_bytes)?;
    let mime = String::from_utf8(mime_bytes)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "asset mime utf8"))?;
    let byte_length = usize::try_from(header.byte_length)
        .ok()
        .filter(|&len| len <= crate::protocol::MAX_STREAMED_ASSET_BYTES)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "asset too large"))?;
    let mut payload = vec![0u8; byte_length];
    reader.read_exact(&mut payload)?;
    Ok((header.hash, mime, payload))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_object_round_trips_the_stream_start_position() {
        let body = vec![7u8; 4096];
        let mut framed = Vec::new();
        write_media_object(&mut framed, 3, body.len() as u64, 84_000, &mut &body[..]).unwrap();

        let mut stream = MediaStreamBody::open(std::io::Cursor::new(framed)).unwrap();
        assert_eq!(stream.generation, 3);
        assert_eq!(stream.start_position_ms, 84_000);
        let mut got = Vec::new();
        stream.read_to_end(&mut got).unwrap();
        assert_eq!(got, body);
    }
}
