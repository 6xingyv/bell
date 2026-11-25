use std::sync::atomic::Ordering;
use types::SharedEngineState;

pub(super) fn normalized_visual_level(rms: f32, peak: f32) -> f32 {
    fn dbfs(value: f32) -> f32 {
        20.0 * value.max(1.0e-6).log10()
    }

    // Raw PCM RMS spends almost all of its useful range below 0.2. Map the
    // musical range logarithmically and retain some peak energy so percussion
    // still produces a visible transient instead of disappearing in an RMS-only
    // average. The exported value is a visual-analysis envelope in 0..1.
    let rms_level = ((dbfs(rms) + 58.0) / 50.0).clamp(0.0, 1.0);
    let peak_level = ((dbfs(peak) + 46.0) / 43.0).clamp(0.0, 1.0);
    (rms_level * 0.78 + peak_level * 0.22)
        .clamp(0.0, 1.0)
        .powf(1.15)
}

pub(super) fn publish_rms(
    state: &SharedEngineState,
    samples: &[f32],
    sample_rate: u32,
    channels: u16,
) {
    let (rms, peak) = if samples.is_empty() {
        (0.0, 0.0)
    } else {
        // Single pass over the buffer on the realtime thread: sum-of-squares and
        // peak together instead of two iterations.
        let (sum, peak) = samples.iter().fold((0.0f32, 0.0f32), |(sum, peak), &sample| {
            (sum + sample * sample, peak.max(sample.abs()))
        });
        ((sum / samples.len() as f32).sqrt(), peak)
    };
    let target = normalized_visual_level(rms, peak);
    let previous = f32::from_bits(state.rms_level.load(Ordering::Relaxed)).clamp(0.0, 1.0);
    let frame_count = samples.len() as f32 / channels.max(1) as f32;
    let dt = frame_count / sample_rate.max(1) as f32;
    let time_constant = if target > previous { 0.025 } else { 0.24 };
    let blend = 1.0 - (-dt / time_constant).exp();
    let envelope = previous + (target - previous) * blend.clamp(0.0, 1.0);
    state.rms_level.store(envelope.to_bits(), Ordering::Relaxed);
}
