#[cfg(not(target_os = "android"))]
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
#[cfg(not(target_os = "android"))]
use cpal::{SampleFormat, Stream, StreamConfig};
#[cfg(target_os = "android")]
use oboe::{
    AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamAsync, AudioStreamBase,
    AudioStreamBuilder, ContentType, DataCallbackResult, Error as OboeError, Output,
    PerformanceMode, SampleRateConversionQuality, SharingMode, Stereo, Usage,
};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use types::SharedEngineState;

use super::level::publish_rms;
use super::{Renderer, RendererShared};

pub struct AudioOutput {
    renderer: Arc<Mutex<Renderer>>,
    renderer_shared: Arc<RendererShared>,
    sample_rate: u32,
    channels: u16,
    device_signature: String,
    reconnect_requested: Arc<AtomicBool>,
    #[cfg(not(target_os = "android"))]
    _stream: Stream,
    #[cfg(target_os = "android")]
    _stream: AudioStreamAsync<Output, AndroidOutputCallback>,
}

#[cfg(target_os = "android")]
struct AndroidOutputCallback {
    renderer: Arc<Mutex<Renderer>>,
    shared_state: Arc<SharedEngineState>,
    reconnect_requested: Arc<AtomicBool>,
}

#[cfg(target_os = "android")]
impl AudioOutputCallback for AndroidOutputCallback {
    type FrameType = (f32, Stereo);

    fn on_audio_ready(
        &mut self,
        stream: &mut dyn AudioOutputStreamSafe,
        audio_data: &mut [(f32, f32)],
    ) -> DataCallbackResult {
        // Oboe supplies stereo PCM as one `(left, right)` value per frame, but
        // Renderer intentionally consumes the native interleaved sample buffer.
        // Oboe's callback storage is two adjacent f32 values per stereo frame,
        // so this view neither allocates nor copies on the real-time thread.
        let samples = unsafe {
            std::slice::from_raw_parts_mut(
                audio_data.as_mut_ptr().cast::<f32>(),
                audio_data.len() * 2,
            )
        };
        if let Ok(mut renderer) = self.renderer.try_lock() {
            renderer.process_output(samples);
        } else {
            samples.fill(0.0);
        }
        publish_rms(
            &self.shared_state,
            samples,
            stream.get_sample_rate().max(1) as u32,
            2,
        );
        DataCallbackResult::Continue
    }

    fn on_error_after_close(&mut self, _stream: &mut dyn AudioOutputStreamSafe, error: OboeError) {
        self.reconnect_requested.store(true, Ordering::Release);
        eprintln!("Oboe output stream disconnected: {error:?}");
    }
}

impl AudioOutput {
    pub fn renderer(&self) -> Arc<Mutex<Renderer>> {
        self.renderer.clone()
    }

    /// Lock-free renderer state for the engine watcher threads.
    pub fn renderer_shared(&self) -> Arc<RendererShared> {
        self.renderer_shared.clone()
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> u16 {
        self.channels
    }

    pub fn device_signature(&self) -> &str {
        &self.device_signature
    }

    pub fn reconnect_requested(&self) -> bool {
        self.reconnect_requested.load(Ordering::Acquire)
    }

    /// A best-effort identity for the current system default. Desktop CPAL
    /// backends expose the endpoint name; including its default format also
    /// catches in-place sample-rate/channel changes. Android route loss is
    /// signalled by Oboe's post-close error callback instead.
    pub fn default_device_signature() -> Option<String> {
        #[cfg(target_os = "android")]
        {
            return Some("android-default-oboe".to_string());
        }
        #[cfg(not(target_os = "android"))]
        {
            let host = cpal::default_host();
            let device = host.default_output_device()?;
            Some(device_signature(&device))
        }
    }

    #[cfg(not(target_os = "android"))]
    pub fn new(
        preferred_sample_rate: Option<u32>,
        shared_state: Arc<SharedEngineState>,
    ) -> Result<Self, anyhow::Error> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| anyhow::anyhow!("No output device available"))?;
        let device_signature = device_signature(&device);

        let config = if let Some(pref_rate) = preferred_sample_rate {
            let mut configs: Vec<_> = device
                .supported_output_configs()?
                .filter(|config| {
                    matches!(
                        config.sample_format(),
                        SampleFormat::F32 | SampleFormat::I16
                    )
                })
                .collect();

            configs.sort_by(|a, b| {
                let score_fmt = |fmt: SampleFormat| match fmt {
                    SampleFormat::F32 => 2,
                    SampleFormat::I16 => 1,
                    _ => 0,
                };
                let cmp_fmt = score_fmt(b.sample_format()).cmp(&score_fmt(a.sample_format()));
                if cmp_fmt != std::cmp::Ordering::Equal {
                    return cmp_fmt;
                }

                let channel_score = |channels: u16| {
                    if channels == 2 {
                        2
                    } else if channels == 1 {
                        1
                    } else {
                        0
                    }
                };
                let cmp_ch = channel_score(b.channels()).cmp(&channel_score(a.channels()));
                if cmp_ch != std::cmp::Ordering::Equal {
                    return cmp_ch;
                }

                b.max_sample_rate().cmp(&a.max_sample_rate())
            });

            let best = configs
                .into_iter()
                .find(|c| pref_rate >= c.min_sample_rate() && pref_rate <= c.max_sample_rate());

            match best {
                Some(c) => c.with_sample_rate(pref_rate),
                None => device.default_output_config()?,
            }
        } else {
            device.default_output_config()?
        };

        let sample_rate: u32 = config.sample_rate();
        let channels = config.channels();
        let format = config.sample_format();

        let renderer = Arc::new(Mutex::new(Renderer::new(channels)));
        let renderer_shared = renderer.lock().unwrap().shared();
        let renderer_clone = renderer.clone();
        let f32_shared_state = shared_state.clone();
        let reconnect_requested = Arc::new(AtomicBool::new(false));

        let stream_config: StreamConfig = config.clone().into();

        let stream = match format {
            SampleFormat::F32 => device.build_output_stream(
                &stream_config,
                move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                    if let Ok(mut rnd) = renderer_clone.lock() {
                        rnd.process_output(data);
                    } else {
                        data.fill(0.0);
                    }
                    publish_rms(&f32_shared_state, data, sample_rate, channels);
                },
                {
                    let reconnect_requested = reconnect_requested.clone();
                    move |err| {
                        reconnect_requested.store(true, Ordering::Release);
                        eprintln!("An error occurred on the audio stream: {}", err);
                    }
                },
                None,
            )?,
            SampleFormat::I16 => {
                let mut f32_buf = Vec::new();
                let shared_state = shared_state.clone();
                device.build_output_stream(
                    &stream_config,
                    move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
                        f32_buf.resize(data.len(), 0.0);
                        // process_output already zeroes the buffer; only fall back
                        // to an explicit clear when the renderer lock is contended.
                        if let Ok(mut rnd) = renderer_clone.lock() {
                            rnd.process_output(&mut f32_buf);
                        } else {
                            f32_buf.fill(0.0);
                        }
                        publish_rms(&shared_state, &f32_buf, sample_rate, channels);
                        for (i, sample) in f32_buf.iter().enumerate() {
                            data[i] = (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
                        }
                    },
                    {
                        let reconnect_requested = reconnect_requested.clone();
                        move |err| {
                            reconnect_requested.store(true, Ordering::Release);
                            eprintln!("An error occurred on the audio stream: {}", err);
                        }
                    },
                    None,
                )?
            }
            _ => return Err(anyhow::anyhow!("Unsupported format: {:?}", format)),
        };

        stream.play()?;

        Ok(Self {
            renderer,
            renderer_shared,
            sample_rate,
            channels,
            device_signature,
            reconnect_requested,
            _stream: stream,
        })
    }

    #[cfg(target_os = "android")]
    pub fn new(
        _preferred_sample_rate: Option<u32>,
        shared_state: Arc<SharedEngineState>,
    ) -> Result<Self, anyhow::Error> {
        let channels = 2u16;
        let renderer = Arc::new(Mutex::new(Renderer::new(channels)));
        let renderer_shared = renderer.lock().unwrap().shared();
        let reconnect_requested = Arc::new(AtomicBool::new(false));
        let callback = AndroidOutputCallback {
            renderer: renderer.clone(),
            shared_state,
            reconnect_requested: reconnect_requested.clone(),
        };

        // Leave the sample rate unspecified so Oboe opens the new route at its
        // native rate. The decoder is retargeted to the actual rate below.
        let mut stream = AudioStreamBuilder::default()
            .set_performance_mode(PerformanceMode::LowLatency)
            .set_sharing_mode(SharingMode::Shared)
            .set_usage(Usage::Media)
            .set_content_type(ContentType::Music)
            .set_sample_rate_conversion_quality(SampleRateConversionQuality::Medium)
            .set_format_conversion_allowed(true)
            .set_channel_conversion_allowed(true)
            .set_format::<f32>()
            .set_channel_count::<Stereo>()
            .set_callback(callback)
            .open_stream()
            .map_err(|error| anyhow::anyhow!("open Oboe output: {error:?}"))?;
        let sample_rate = stream.get_sample_rate().max(1) as u32;
        stream
            .start()
            .map_err(|error| anyhow::anyhow!("start Oboe output: {error:?}"))?;

        Ok(Self {
            renderer,
            renderer_shared,
            sample_rate,
            channels,
            device_signature: "android-default-oboe".to_string(),
            reconnect_requested,
            _stream: stream,
        })
    }
}

#[cfg(not(target_os = "android"))]
fn device_signature(device: &cpal::Device) -> String {
    let name = device.name().unwrap_or_else(|_| "<unnamed>".to_string());
    match device.default_output_config() {
        Ok(config) => format!(
            "{name}|{}|{}|{:?}",
            config.sample_rate(),
            config.channels(),
            config.sample_format(),
        ),
        Err(_) => name,
    }
}
