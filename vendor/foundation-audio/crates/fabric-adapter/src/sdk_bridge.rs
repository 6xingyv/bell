//! Binds the audio control protocol to the fabric SDK session ports.
//!
//! This is the async **protocol engine ↔ sdk** layer from
//! `fabric/docs/11-reference-abilities.md`: it maps a fabric `MessagePort` (the
//! bidirectional `control` port) to [`AudioControlMessage`], so a controller can
//! route `Play`/`Pause`/`Seek`/`Stop` back to the source (task 3) and the source
//! can rebroadcast group state — over the real SDK port shape.
//!
//! Status: foundation.fabric currently ships the SDK as **traits only** (no
//! concrete `FabricClient`/`SessionHandle` runtime, no hub-side session wiring).
//! This module therefore compiles and is unit-tested against an in-memory mock
//! `MessagePort`; it becomes live unchanged once fabric provides a client
//! runtime that yields real sessions and ports. Every remaining link
//! (`jni-bridge` entry points, clef's Kotlin controller/UI) plugs into this.

use crate::protocol::AudioControlMessage;
use crate::time::{GroupClock, GroupInstantNs, LocalInstantNs, ProcessMonotonicClock, TimeMapError};
use bytes::Bytes;
use fabric_sdk::{FabricError, MessagePort, SessionClock, SessionHandle};
use std::sync::Arc;

/// Serialize a control message for a fabric message port (JSON, matching the
/// rest of the ability's control-plane encoding).
#[must_use]
pub fn encode(message: &AudioControlMessage) -> Bytes {
    Bytes::from(serde_json::to_vec(message).unwrap_or_default())
}

/// Deserialize a control message received on a fabric message port.
pub fn decode(payload: &[u8]) -> Result<AudioControlMessage, serde_json::Error> {
    serde_json::from_slice(payload)
}

fn is_transport_command(message: &AudioControlMessage) -> bool {
    matches!(
        message,
        AudioControlMessage::Play { .. }
            | AudioControlMessage::Pause { .. }
            | AudioControlMessage::Stop { .. }
            | AudioControlMessage::Seek { .. }
    )
}

/// The audio `control` port bound to an [`AudioControlMessage`] codec.
///
/// Bidirectional: source→renderers carries announce/asset/barrier; renderer or
/// controller→source carries `AssetRequest`/`Ready`/`Reject` and the transport
/// commands. Both directions ride this one port; the hub routes by session plan.
pub struct ControlChannel {
    port: Box<dyn MessagePort>,
}

impl ControlChannel {
    /// Open the ability's `control` message port on a live session.
    pub async fn open(session: &dyn SessionHandle) -> Result<Self, FabricError> {
        let port = session.open_message_port(crate::CONTROL_PORT).await?;
        Ok(Self { port })
    }

    /// Build directly from a message port (used by platform bindings and tests).
    #[must_use]
    pub fn from_port(port: Box<dyn MessagePort>) -> Self {
        Self { port }
    }

    /// Send one control message. For a source this fans out to renderers per the
    /// session plan; for a controller it reaches the source.
    pub async fn send(&self, message: &AudioControlMessage) -> Result<(), FabricError> {
        self.port.send(encode(message)).await
    }

    /// Await the next control message, decoded.
    pub async fn recv(&self) -> Result<AudioControlMessage, FabricError> {
        let received = self.port.receive().await?;
        decode(&received.payload).map_err(|e| FabricError::InvalidRequest(e.to_string()))
    }
}

/// Controller role: routes local transport intents back to the source over the
/// bidirectional control port (task 3).
pub struct AudioController {
    channel: ControlChannel,
}

impl AudioController {
    pub async fn open(session: &dyn SessionHandle) -> Result<Self, FabricError> {
        Ok(Self {
            channel: ControlChannel::open(session).await?,
        })
    }

    #[must_use]
    pub fn from_channel(channel: ControlChannel) -> Self {
        Self { channel }
    }

    pub async fn play(&self, generation: u64) -> Result<(), FabricError> {
        self.channel
            .send(&AudioControlMessage::Play { generation })
            .await
    }

    pub async fn pause(&self, generation: u64) -> Result<(), FabricError> {
        self.channel
            .send(&AudioControlMessage::Pause { generation })
            .await
    }

    pub async fn stop(&self, generation: u64) -> Result<(), FabricError> {
        self.channel
            .send(&AudioControlMessage::Stop { generation })
            .await
    }

    pub async fn seek(&self, generation: u64, position_ms: u64) -> Result<(), FabricError> {
        self.channel
            .send(&AudioControlMessage::Seek {
                generation,
                position_ms,
            })
            .await
    }
}

/// Drive the source side of the bidirectional control port: receive commands a
/// renderer/controller routed back, rebroadcast group state to all renderers,
/// and hand each transport command to `on_command` for the local engine (task 3).
///
/// Runs until the port disconnects. `on_command` sees only transport commands;
/// other inbound messages (`AssetRequest`, `Ready`, `Reject`, …) go to
/// `on_message` so callers can drive asset serving / the barrier.
pub async fn run_source_control_loop<C, M>(
    channel: &ControlChannel,
    mut on_command: C,
    mut on_message: M,
) -> Result<(), FabricError>
where
    C: FnMut(&AudioControlMessage),
    M: FnMut(&AudioControlMessage),
{
    loop {
        let message = channel.recv().await?;
        if is_transport_command(&message) {
            // The source is the single writer of group state: rebroadcast so
            // every renderer follows the command that was routed back to us.
            channel.send(&message).await?;
            on_command(&message);
        } else {
            on_message(&message);
        }
    }
}

/// Adapts a fabric [`SessionClock`] to the ability's [`GroupClock`]. Local time
/// comes from a process-monotonic origin; group mapping is delegated to fabric.
pub struct SessionGroupClock {
    clock: Arc<dyn SessionClock>,
    mono: ProcessMonotonicClock,
}

impl SessionGroupClock {
    #[must_use]
    pub fn new(clock: Arc<dyn SessionClock>) -> Self {
        Self {
            clock,
            mono: ProcessMonotonicClock::new(),
        }
    }
}

impl GroupClock for SessionGroupClock {
    fn local_now(&self) -> LocalInstantNs {
        LocalInstantNs(self.mono.now_ns())
    }

    fn group_to_local(&self, group: GroupInstantNs) -> Result<LocalInstantNs, TimeMapError> {
        self.clock
            .group_to_local(group.0)
            .map(LocalInstantNs)
            .map_err(|_| TimeMapError::Invalid)
    }

    fn local_to_group(&self, local: LocalInstantNs) -> Result<GroupInstantNs, TimeMapError> {
        self.clock
            .local_to_group(local.0)
            .map(GroupInstantNs)
            .map_err(|_| TimeMapError::Invalid)
    }

    fn uncertainty_ns(&self) -> u64 {
        self.clock.uncertainty_ns()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use fabric_core::{ParticipantId, PortId};
    use fabric_sdk::ReceivedMessage;
    use std::sync::Mutex as StdMutex;
    use tokio::sync::{mpsc, Mutex};

    /// In-memory `MessagePort`: sends on `tx`, receives on `rx`. Two of these
    /// cross-wired model the source↔controller control route the hub would build.
    struct MockPort {
        sender: ParticipantId,
        tx: mpsc::UnboundedSender<Bytes>,
        rx: Mutex<mpsc::UnboundedReceiver<Bytes>>,
    }

    #[async_trait]
    impl MessagePort for MockPort {
        async fn send(&self, payload: Bytes) -> Result<(), FabricError> {
            self.tx.send(payload).map_err(|_| FabricError::Disconnected)
        }
        async fn receive(&self) -> Result<ReceivedMessage, FabricError> {
            let payload = self
                .rx
                .lock()
                .await
                .recv()
                .await
                .ok_or(FabricError::Disconnected)?;
            Ok(ReceivedMessage {
                sender: self.sender,
                port: PortId::new(),
                payload,
            })
        }
    }

    fn wired_pair() -> (Box<dyn MessagePort>, Box<dyn MessagePort>) {
        let (a_tx, a_rx) = mpsc::unbounded_channel();
        let (b_tx, b_rx) = mpsc::unbounded_channel();
        let controller = MockPort {
            sender: ParticipantId::new(),
            tx: a_tx,
            rx: Mutex::new(b_rx),
        };
        let source = MockPort {
            sender: ParticipantId::new(),
            tx: b_tx,
            rx: Mutex::new(a_rx),
        };
        (Box::new(controller), Box::new(source))
    }

    #[tokio::test]
    async fn controller_seek_routes_to_source_and_is_applied() {
        let (controller_port, source_port) = wired_pair();
        let controller = AudioController::from_channel(ControlChannel::from_port(controller_port));
        let source = ControlChannel::from_port(source_port);

        let applied: Arc<StdMutex<Vec<AudioControlMessage>>> = Arc::new(StdMutex::new(Vec::new()));
        let seen = applied.clone();
        let source_loop = tokio::spawn(async move {
            let _ = run_source_control_loop(
                &source,
                |cmd| seen.lock().unwrap().push(cmd.clone()),
                |_| {},
            )
            .await;
        });

        // A controller on the far device routes a Seek back to the source.
        controller.seek(4, 42_000).await.unwrap();
        // The source rebroadcasts group state; the controller observes it.
        let echoed = controller.channel.recv().await.unwrap();
        assert_eq!(
            echoed,
            AudioControlMessage::Seek {
                generation: 4,
                position_ms: 42_000
            }
        );

        // Give the source loop a moment to record the applied command.
        for _ in 0..100 {
            if !applied.lock().unwrap().is_empty() {
                break;
            }
            tokio::task::yield_now().await;
        }
        source_loop.abort();
        let applied = applied.lock().unwrap();
        assert_eq!(
            applied.as_slice(),
            &[AudioControlMessage::Seek {
                generation: 4,
                position_ms: 42_000
            }]
        );
    }

    #[test]
    fn transport_commands_are_classified() {
        assert!(is_transport_command(&AudioControlMessage::Pause { generation: 1 }));
        assert!(!is_transport_command(&AudioControlMessage::Ping));
    }
}
