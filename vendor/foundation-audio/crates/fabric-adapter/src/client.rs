//! Fabric **client SDK** binding for the audio ability.
//!
//! Talks to a running hub through `fabric-client` (the broker client) instead
//! of hand-rolled transports: control messages ride `FabricClient::invoke`
//! against [`crate::AUDIO_RENDER_ABILITY`], and bulk media / asset bytes ride
//! named broker streams ([`crate::MEDIA_STREAM`] / [`crate::ASSET_STREAM`]).
//! This is the source/controller side; the renderer keeps serving through
//! `LocalAudioRenderAdapter::handle_control_json` + `attach_media_stream`.

use crate::protocol::AudioControlMessage;
use fabric_client::{BrokerError, FabricClient};
use tokio::net::TcpStream;

/// Decoded reply from one control invoke on the render ability.
///
/// The renderer answers with either a JSON array of protocol replies
/// (`Ready`/`Reject`/`AssetRequest`, …) or a simple `{"ok": …}` status object.
#[derive(Clone, Debug, PartialEq)]
pub enum ControlReply {
    /// Protocol replies routed back from the renderer.
    Replies(Vec<AudioControlMessage>),
    /// Simple status acknowledgement.
    Status { ok: bool, error: Option<String> },
}

/// Errors from the client-SDK audio binding.
#[derive(Debug)]
pub enum ClientError {
    Broker(BrokerError),
    /// The renderer's reply was not a known control-reply shape.
    Reply(String),
    /// Stream I/O failed while pushing media/asset bytes.
    Io(std::io::Error),
}

impl std::fmt::Display for ClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Broker(error) => write!(f, "broker: {error}"),
            Self::Reply(error) => write!(f, "reply: {error}"),
            Self::Io(error) => write!(f, "io: {error}"),
        }
    }
}

impl std::error::Error for ClientError {}

impl From<BrokerError> for ClientError {
    fn from(error: BrokerError) -> Self {
        Self::Broker(error)
    }
}

impl From<std::io::Error> for ClientError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

fn decode_reply(payload: &[u8]) -> Result<ControlReply, ClientError> {
    if let Ok(replies) = serde_json::from_slice::<Vec<AudioControlMessage>>(payload) {
        return Ok(ControlReply::Replies(replies));
    }
    #[derive(serde::Deserialize)]
    struct Status {
        ok: bool,
        #[serde(default)]
        error: Option<String>,
    }
    serde_json::from_slice::<Status>(payload)
        .map(|status| ControlReply::Status {
            ok: status.ok,
            error: status.error,
        })
        .map_err(|error| ClientError::Reply(error.to_string()))
}

/// Audio render ability on one fabric device, reached through the hub broker.
///
/// Cheap to construct; each call opens its own short-lived broker connection
/// (the `fabric-client` model), so one value can be shared freely.
pub struct AudioFabricClient {
    fabric: FabricClient,
    device: String,
}

impl AudioFabricClient {
    /// Bind to `device` (a broker device id, or `fabric_client::LOCAL_DEVICE_ID`)
    /// through an existing client handle.
    #[must_use]
    pub fn new(fabric: FabricClient, device: impl Into<String>) -> Self {
        Self {
            fabric,
            device: device.into(),
        }
    }

    /// Bind through the hub on this machine.
    #[must_use]
    pub fn local(device: impl Into<String>) -> Self {
        Self::new(FabricClient::local(), device)
    }

    #[must_use]
    pub fn device(&self) -> &str {
        &self.device
    }

    /// Send one control message and decode the renderer's reply.
    pub async fn send_control(
        &self,
        message: &AudioControlMessage,
    ) -> Result<ControlReply, ClientError> {
        let payload = serde_json::to_vec(message)
            .map_err(|error| ClientError::Reply(error.to_string()))?;
        let reply = self
            .fabric
            .invoke(&self.device, crate::AUDIO_RENDER_ABILITY, payload)
            .await?;
        decode_reply(&reply)
    }

    pub async fn play(&self, generation: u64) -> Result<ControlReply, ClientError> {
        self.send_control(&AudioControlMessage::Play { generation })
            .await
    }

    pub async fn pause(&self, generation: u64) -> Result<ControlReply, ClientError> {
        self.send_control(&AudioControlMessage::Pause { generation })
            .await
    }

    pub async fn stop(&self, generation: u64) -> Result<ControlReply, ClientError> {
        self.send_control(&AudioControlMessage::Stop { generation })
            .await
    }

    pub async fn seek(&self, generation: u64, position_ms: u64) -> Result<ControlReply, ClientError> {
        self.send_control(&AudioControlMessage::Seek {
            generation,
            position_ms,
        })
        .await
    }

    /// Open the bulk media stream: the source writes the framed media object
    /// (see `write_media_object`) and the renderer attaches it to its engine.
    /// Prefer [`send_media`](Self::send_media), which frames for you.
    pub async fn open_media_stream(&self) -> Result<TcpStream, ClientError> {
        Ok(self
            .fabric
            .open_stream(&self.device, crate::AUDIO_RENDER_ABILITY, crate::MEDIA_STREAM)
            .await?)
    }

    /// Open the asset stream for one framed cover/lyrics object
    /// (see `write_asset_stream_object`), announced via `AssetStreamAnnounce`.
    /// Prefer [`send_asset`](Self::send_asset), which frames for you.
    pub async fn open_asset_stream(&self) -> Result<TcpStream, ClientError> {
        Ok(self
            .fabric
            .open_stream(&self.device, crate::AUDIO_RENDER_ABILITY, crate::ASSET_STREAM)
            .await?)
    }

    /// Open the media stream and push one framed media object: header
    /// (generation, byte length, stream start position) followed by the
    /// compressed container bytes from `body`. `start_position_ms` is the
    /// media-timeline position at which `body`'s byte 0 begins — pass the seek
    /// target when restarting delivery after a `Seek`, `0` from the top.
    /// Returns the body bytes written.
    pub async fn send_media<R>(
        &self,
        generation: u64,
        byte_length: u64,
        start_position_ms: u64,
        mut body: R,
    ) -> Result<u64, ClientError>
    where
        R: std::io::Read + Send + 'static,
    {
        let stream = self.open_media_stream().await?;
        let stream = stream.into_std()?;
        stream.set_nonblocking(false)?;
        tokio::task::spawn_blocking(move || {
            let mut writer = stream;
            crate::transport::write_media_object(
                &mut writer,
                generation,
                byte_length,
                start_position_ms,
                &mut body,
            )
        })
        .await
        .map_err(|join| ClientError::Reply(join.to_string()))?
        .map_err(ClientError::Io)
    }

    /// Open the asset stream and push one framed cover/lyrics object. Announce
    /// it first via [`send_control`](Self::send_control) with
    /// `AssetStreamAnnounce`. Returns once the object is fully written.
    pub async fn send_asset(
        &self,
        hash: crate::protocol::ContentHash,
        mime: String,
        bytes: Vec<u8>,
    ) -> Result<(), ClientError> {
        let stream = self.open_asset_stream().await?;
        let stream = stream.into_std()?;
        stream.set_nonblocking(false)?;
        tokio::task::spawn_blocking(move || {
            let mut writer = stream;
            crate::transport::write_asset_stream_object(&mut writer, hash, &mime, &bytes)
        })
        .await
        .map_err(|join| ClientError::Reply(join.to_string()))?
        .map_err(ClientError::Io)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_reply_decodes() {
        let reply = decode_reply(br#"{"ok":true,"error":null,"status":null}"#).unwrap();
        assert_eq!(
            reply,
            ControlReply::Status {
                ok: true,
                error: None
            }
        );
    }

    #[test]
    fn protocol_replies_decode() {
        let replies = vec![AudioControlMessage::AssetRequest { hash: [7u8; 32] }];
        let payload = serde_json::to_vec(&replies).unwrap();
        assert_eq!(
            decode_reply(&payload).unwrap(),
            ControlReply::Replies(replies)
        );
    }

    #[test]
    fn garbage_reply_is_an_error() {
        assert!(matches!(
            decode_reply(b"not json"),
            Err(ClientError::Reply(_))
        ));
    }
}
