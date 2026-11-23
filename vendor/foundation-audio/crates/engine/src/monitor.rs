use super::{PlaybackEngine, TAG, TrackSession, orchestration};
use renderer::{AudioOutput, Renderer, RendererShared};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use types::PlayerState;
use util::log_i;

impl PlaybackEngine {
    pub(super) fn spawn_output_device_monitor(engine: &Arc<Self>) {
        let weak = Arc::downgrade(engine);
        thread::spawn(move || {
            loop {
                thread::sleep(Duration::from_millis(500));
                let Some(engine) = weak.upgrade() else {
                    break;
                };

                let should_rebuild = {
                    let output = engine.output.lock().unwrap();
                    output.as_ref().is_some_and(|output| {
                        output.reconnect_requested()
                            || AudioOutput::default_device_signature()
                                .is_some_and(|current| current != output.device_signature())
                    })
                };
                if should_rebuild {
                    engine.rebuild_output_device();
                }
            }
        });
    }

    fn rebuild_output_device(&self) {
        let _transition = self.transition.lock().unwrap();
        let state = self.state();
        if state == PlayerState::Stopped {
            // There is no decoder timeline to preserve. The next playback will
            // lazily open the then-current default device.
            self.output.lock().unwrap().take();
            return;
        }

        let item = match self.queue.lock().unwrap().current_entry() {
            Some(item) => item,
            None => return,
        };
        let sample_rate = self.shared_state.sample_rate.load(Ordering::Relaxed);
        let current_frame = self.shared_state.current_frame.load(Ordering::Relaxed);
        let position_ms = if sample_rate == 0 {
            0
        } else {
            current_frame.saturating_mul(1000) / u64::from(sample_rate)
        };
        let paused = state == PlayerState::Paused;

        if self.start_playback_at_locked_with_output(item, position_ms, true, paused, false, 0, None)
        {
            log_i!(
                TAG,
                "reopened default output at {} ms (paused={})",
                position_ms,
                paused
            );
        }
    }

    pub(super) fn cancel_current_session(&self) {
        if let Some(sess) = self.current_session.lock().unwrap().take() {
            sess.cancel();
        }
    }

    pub(crate) fn is_current_session(&self, session: &Arc<TrackSession>) -> bool {
        self.current_session
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, session))
    }

    pub(crate) fn spawn_watcher(
        self: &Self,
        session: Arc<TrackSession>,
        renderer: Arc<Mutex<Renderer>>,
        renderer_shared: Arc<RendererShared>,
        sample_rate: u32,
        channels: u16,
    ) {
        let engine = self.arc_self();
        thread::spawn(move || {
            let duration_ms = session.duration_ms;
            let mut triggered_crossfade = false;

            loop {
                if session.is_cancelled() {
                    break;
                }

                // Read the renderer's live state lock-free — never contend the
                // renderer mutex with the realtime audio callback.
                let current_f = renderer_shared.current_frame();
                let is_paused = renderer_shared.is_paused();
                let lead_is_empty = renderer_shared.lead_is_empty();

                engine
                    .shared_state
                    .current_frame
                    .store(current_f, Ordering::Relaxed);
                engine
                    .shared_state
                    .is_paused
                    .store(is_paused as u32, Ordering::Relaxed);
                engine
                    .shared_state
                    .buffered_samples
                    .store(renderer_shared.lead_occupied_samples(), Ordering::Relaxed);
                engine
                    .shared_state
                    .underruns
                    .store(renderer_shared.underruns(), Ordering::Relaxed);

                if is_paused {
                    thread::sleep(Duration::from_millis(100));
                    continue;
                }

                let xfade_ms = engine.crossfade_duration_ms() as u64;
                if xfade_ms > 0 && duration_ms > xfade_ms {
                    let cross_start_frame = ((duration_ms - xfade_ms) * sample_rate as u64) / 1000;
                    if current_f >= cross_start_frame && !triggered_crossfade {
                        triggered_crossfade = true;
                        if engine.trigger_crossfade(
                            &session,
                            &renderer,
                            &renderer_shared,
                            sample_rate,
                            channels,
                            cross_start_frame,
                            xfade_ms,
                        ) {
                            break;
                        }
                    }
                }

                // The container duration is only a hint. It is commonly rounded
                // down and can differ from the number of decoded PCM frames due
                // to codec delay/padding. Advancing as soon as the rendered
                // timeline reaches that value can discard still-buffered tail
                // samples (especially when Android's callback cadence makes the
                // watcher observe the boundary between callbacks).
                //
                // EOF is determined by the decoder and the output queue instead:
                // once decoding has finished and the lead queue is empty, every
                // decoded sample has been handed to the audio callback.
                let drained_after_decode = session.is_decoder_done() && lead_is_empty;
                if drained_after_decode {
                    engine.advance_at_end(&session);
                    break;
                }

                thread::sleep(Duration::from_millis(50));
            }
        });
    }

    #[allow(clippy::too_many_arguments)]
    fn trigger_crossfade(
        &self,
        session: &Arc<TrackSession>,
        renderer: &Arc<Mutex<Renderer>>,
        renderer_shared: &Arc<RendererShared>,
        sample_rate: u32,
        channels: u16,
        cross_start_frame: u64,
        xfade_ms: u64,
    ) -> bool {
        orchestration::trigger_crossfade(
            self,
            session,
            renderer,
            renderer_shared,
            sample_rate,
            channels,
            cross_start_frame,
            xfade_ms,
        )
    }

    fn advance_at_end(&self, session: &Arc<TrackSession>) {
        let _transition = self.transition.lock().unwrap();
        if session.is_cancelled() || !self.is_current_session(session) {
            return;
        }
        let mode = self.play_mode();
        let (previous, next) = {
            let mut queue = self.queue.lock().unwrap();
            (queue.current_index(), queue.next_entry(mode))
        };
        match next {
            Some(item) => {
                if !self.start_playback_locked(item) {
                    self.queue.lock().unwrap().restore_current_index(previous);
                    self.stop_locked();
                }
            }
            None => {
                self.stop_locked();
            }
        }
    }
}
