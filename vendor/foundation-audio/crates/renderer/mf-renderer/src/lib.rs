use ringbuf::traits::Consumer;
use ringbuf::HeapCons;
use std::sync::{Arc, Mutex};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Stream, StreamConfig, SampleFormat};

pub trait AudioRecipe: Send {
    fn process(&mut self, buffer: &mut [f32], current_frame: u64, channels: u16);
    fn take_follow_consumer(&mut self) -> Option<HeapCons<f32>> { None }
    fn is_crossfade(&self) -> bool { false }
}

pub struct ReplayGainRecipe {
    pub scale: f32,
}

impl ReplayGainRecipe {
    pub fn new(gain_db: f32) -> Self {
        // convert dB to linear scale: 10^(dB/20)
        Self {
            scale: 10.0_f32.powf(gain_db / 20.0),
        }
    }
}

impl AudioRecipe for ReplayGainRecipe {
    fn process(&mut self, buffer: &mut [f32], _current_frame: u64, _channels: u16) {
        for sample in buffer.iter_mut() {
            *sample *= self.scale;
        }
    }
}

pub struct CrossfadeRecipe {
    pub start_frame: u64,
    pub duration_frames: u64,
    pub follow_consumer: Option<HeapCons<f32>>,
    pub scratch_buffer: Vec<f32>,
}

impl CrossfadeRecipe {
    pub fn new(start_frame: u64, duration_frames: u64, follow_consumer: HeapCons<f32>) -> Self {
        Self {
            start_frame,
            duration_frames,
            follow_consumer: Some(follow_consumer),
            scratch_buffer: Vec::with_capacity(4096),
        }
    }
}

impl AudioRecipe for CrossfadeRecipe {
    fn process(&mut self, buffer: &mut [f32], current_frame: u64, channels: u16) {
        let len = buffer.len();
        if self.scratch_buffer.len() < len {
            self.scratch_buffer.resize(len, 0.0);
        }


        self.scratch_buffer.fill(0.0);
        let follow_read = if let Some(cons) = &mut self.follow_consumer {
            cons.pop_slice(&mut self.scratch_buffer[..len])
        } else {
            0
        };
        
        for i in 0..len {
            let frame_idx = current_frame + (i / channels as usize) as u64;
            
            let fade_factor = if frame_idx < self.start_frame {
                0.0
            } else if frame_idx >= self.start_frame + self.duration_frames {
                1.0
            } else {
                (frame_idx - self.start_frame) as f32 / self.duration_frames as f32
            };

            let lead_gain = (1.0 - fade_factor).sqrt();
            let follow_gain = fade_factor.sqrt();

            // Apply gains. If follow_read < i, follow_buffer[i] is 0.0 (from fill)
            let lead_part = buffer[i] * lead_gain;
            let follow_part = if i < follow_read { self.scratch_buffer[i] * follow_gain } else { 0.0 };
            
            buffer[i] = lead_part + follow_part;
        }
    }

    fn take_follow_consumer(&mut self) -> Option<HeapCons<f32>> {
        self.follow_consumer.take()
    }

    fn is_crossfade(&self) -> bool {
        true
    }
}

pub struct Renderer {
    pub lead_consumer: Option<HeapCons<f32>>,
    pub recipe_chain: Vec<Box<dyn AudioRecipe>>,
    pub current_frame: u64,
    pub channels: u16,
    pub is_paused: bool,
}

impl Renderer {
    pub fn new(channels: u16) -> Self {
        Self {
            lead_consumer: None,
            recipe_chain: Vec::new(),
            current_frame: 0,
            channels,
            is_paused: false,
        }
    }

    pub fn process_output(&mut self, output_buffer: &mut [f32]) {
        output_buffer.fill(0.0);

        if self.is_paused {
            return;
        }

        // Always try to read from lead_consumer first
        let read_len = if let Some(consumer) = &mut self.lead_consumer {
            consumer.pop_slice(output_buffer)
        } else {
            0
        };

        // Even if lead_consumer is empty, we might have recipes (like Crossfade) 
        // that provide audio from other sources into the output_buffer.
        // We use the full output_buffer size for recipes if the lead is silent.
        let process_len = if read_len > 0 { read_len } else { output_buffer.len() };
        let active_slice = &mut output_buffer[..process_len];

        for recipe in &mut self.recipe_chain {
            recipe.process(active_slice, self.current_frame, self.channels);
        }
        
        // Advance current frame by the number of frames we processed
        self.current_frame += (process_len / self.channels as usize) as u64;
    }
}

pub struct AudioOutput {
    pub renderer: Arc<Mutex<Renderer>>,
    pub sample_rate: u32,
    pub channels: u16,
    _stream: Stream,
}

impl AudioOutput {
    pub fn new(preferred_sample_rate: Option<u32>) -> Result<Self, anyhow::Error> {
        let host = cpal::default_host();
        let device = host.default_output_device().ok_or_else(|| anyhow::anyhow!("No output device available"))?;
        
        let config = if let Some(pref_rate) = preferred_sample_rate {
            let mut configs: Vec<_> = device.supported_output_configs()?.collect();

            configs.sort_by(|a, b| {
                let score_fmt = |fmt: SampleFormat| match fmt {
                    SampleFormat::F64 => 3,
                    SampleFormat::F32 => 2,
                    SampleFormat::I16 => 1,
                    _ => 0,
                };
                let cmp_fmt = score_fmt(b.sample_format()).cmp(&score_fmt(a.sample_format()));
                if cmp_fmt != std::cmp::Ordering::Equal {
                    return cmp_fmt;
                }

                let cmp_ch = b.channels().cmp(&a.channels());
                if cmp_ch != std::cmp::Ordering::Equal {
                    return cmp_ch;
                }

                b.max_sample_rate().cmp(&a.max_sample_rate())
            });

            let best = configs.into_iter().find(|c| {
                pref_rate >= c.min_sample_rate() && pref_rate <= c.max_sample_rate()
            });

            match best {
                Some(c) => c.with_sample_rate(pref_rate),
                None => device.default_output_config()?
            }
        } else {
            device.default_output_config()?
        };

        let sample_rate: u32 = config.sample_rate();
        let channels = config.channels();
        let format = config.sample_format();

        let renderer = Arc::new(Mutex::new(Renderer::new(channels)));
        let renderer_clone = renderer.clone();

        let stream_config: StreamConfig = config.clone().into();

        let stream = match format {
            SampleFormat::F32 => {
                device.build_output_stream(
                    &stream_config,
                    move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                        if let Ok(mut rnd) = renderer_clone.lock() {
                            rnd.process_output(data);
                        } else {
                            data.fill(0.0);
                        }
                    },
                    |err| eprintln!("An error occurred on the audio stream: {}", err),
                    None,
                )?
            }
            SampleFormat::I16 => {
                device.build_output_stream(
                    &stream_config,
                    move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
                        let mut f32_buf = vec![0.0; data.len()];
                        if let Ok(mut rnd) = renderer_clone.lock() {
                            rnd.process_output(&mut f32_buf);
                        }
                        for (i, sample) in f32_buf.iter().enumerate() {
                            data[i] = (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
                        }
                    },
                    |err| eprintln!("An error occurred on the audio stream: {}", err),
                    None,
                )?
            }
            _ => return Err(anyhow::anyhow!("Unsupported format: {:?}", format)),
        };

        stream.play()?;

        Ok(Self {
            renderer,
            sample_rate,
            channels,
            _stream: stream,
        })
    }
}
