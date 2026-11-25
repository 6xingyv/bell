//! JNI thin-wrapper around engine::PlaybackEngine.
//!
//! Every Java_… here converts arguments, calls a single engine method,
//! and returns. No orchestration, no threads, no fd lifetime tricks.

mod analysis;
mod bindings;
mod clock;
mod fabric_bindings;
mod metadata_bindings;
mod metadata_wire;
mod state;

pub(crate) use state::engine;
