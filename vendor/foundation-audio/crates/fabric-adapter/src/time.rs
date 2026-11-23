//! Thin time mapping surface for the audio ability.
//!
//! Fabric owns ClockDomain estimation (`fabric-clock`) and exposes
//! `SessionClock` on the SDK session handle. This module only defines the
//! mapping trait the ability protocol engine needs so `foundation.audio`
//! never depends on the hub or reimplements NTP-style probing.

use std::time::{Duration, Instant};

/// Nanoseconds on the local monotonic clock (process domain).
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct LocalInstantNs(pub u64);

/// Nanoseconds on the fabric session group clock.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct GroupInstantNs(pub i128);

/// Ability-facing view of fabric SessionClock.
pub trait GroupClock: Send + Sync {
    fn local_now(&self) -> LocalInstantNs;
    fn group_to_local(&self, group: GroupInstantNs) -> Result<LocalInstantNs, TimeMapError>;
    fn local_to_group(&self, local: LocalInstantNs) -> Result<GroupInstantNs, TimeMapError>;
    fn uncertainty_ns(&self) -> u64;
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TimeMapError {
    Stale,
    Uncertain,
    Invalid,
}

/// Process-local monotonic clock used for scheduled resume after the protocol
/// engine has already converted GroupInstant → local.
#[derive(Debug)]
pub struct ProcessMonotonicClock {
    origin: Instant,
}

impl Clone for ProcessMonotonicClock {
    fn clone(&self) -> Self {
        // Share the same origin so absolute deadlines remain comparable.
        Self {
            origin: self.origin,
        }
    }
}

impl Default for ProcessMonotonicClock {
    fn default() -> Self {
        Self {
            origin: Instant::now(),
        }
    }
}

impl ProcessMonotonicClock {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn now_ns(&self) -> u64 {
        self.origin.elapsed().as_nanos().min(u64::MAX as u128) as u64
    }

    pub fn sleep_until_ns(&self, target_ns: u64) {
        let now = self.now_ns();
        if target_ns <= now {
            return;
        }
        let delay = Duration::from_nanos(target_ns - now);
        std::thread::sleep(delay);
    }
}

/// Identity mapping for single-device tests or when fabric clock is unavailable.
/// `group_ns` is treated as already-local.
#[derive(Default)]
pub struct IdentityGroupClock {
    mono: ProcessMonotonicClock,
}

impl IdentityGroupClock {
    pub fn new() -> Self {
        Self::default()
    }
}

impl GroupClock for IdentityGroupClock {
    fn local_now(&self) -> LocalInstantNs {
        LocalInstantNs(self.mono.now_ns())
    }

    fn group_to_local(&self, group: GroupInstantNs) -> Result<LocalInstantNs, TimeMapError> {
        if group.0 < 0 {
            return Err(TimeMapError::Invalid);
        }
        Ok(LocalInstantNs(group.0 as u64))
    }

    fn local_to_group(&self, local: LocalInstantNs) -> Result<GroupInstantNs, TimeMapError> {
        Ok(GroupInstantNs(i128::from(local.0)))
    }

    fn uncertainty_ns(&self) -> u64 {
        0
    }
}

/// Adapter around an already-estimated offset: `remote = local + offset`.
///
/// Useful when the platform bridges fabric SessionClock into the audio ability
/// with a simple affine model. Prefer the real SessionClock in production.
#[derive(Clone, Debug)]
pub struct OffsetGroupClock {
    mono: ProcessMonotonicClock,
    /// group = local + offset
    offset_ns: i128,
    uncertainty_ns: u64,
}

impl OffsetGroupClock {
    pub fn new(offset_ns: i128, uncertainty_ns: u64) -> Self {
        Self {
            mono: ProcessMonotonicClock::new(),
            offset_ns,
            uncertainty_ns,
        }
    }
}

impl GroupClock for OffsetGroupClock {
    fn local_now(&self) -> LocalInstantNs {
        LocalInstantNs(self.mono.now_ns())
    }

    fn group_to_local(&self, group: GroupInstantNs) -> Result<LocalInstantNs, TimeMapError> {
        let local = group.0.checked_sub(self.offset_ns).ok_or(TimeMapError::Invalid)?;
        if local < 0 {
            return Err(TimeMapError::Invalid);
        }
        Ok(LocalInstantNs(local as u64))
    }

    fn local_to_group(&self, local: LocalInstantNs) -> Result<GroupInstantNs, TimeMapError> {
        Ok(GroupInstantNs(
            i128::from(local.0).saturating_add(self.offset_ns),
        ))
    }

    fn uncertainty_ns(&self) -> u64 {
        self.uncertainty_ns
    }
}
