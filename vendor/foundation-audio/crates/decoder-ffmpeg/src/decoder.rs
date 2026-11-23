use super::fd_io::{cleanup_fd_io, open_format_ctx_from_fd};
use super::*;

impl Drop for FFmpegDecoder {
    fn drop(&mut self) {
        self.format_ctx.take();
        unsafe { cleanup_fd_io(self.avio_ctx, self.io_ctx); }
    }
}

impl FFmpegDecoder {
pub fn new<P: AsRef<Path>>(path: P, target_sample_rate: u32, target_channels: u16) -> Result<Self, ffmpeg_next::Error> {
        log_info!("=== FFmpegDecoder::new START ===");
        log_info!("path={:?}, target_sample_rate={}, target_channels={}", path.as_ref(), target_sample_rate, target_channels);
        
        ffmpeg_next::init()?;
        let format_ctx = ffmpeg_next::format::input(&path)?;

        let stream = format_ctx.streams().best(ffmpeg_next::media::Type::Audio)
            .ok_or(ffmpeg_next::Error::StreamNotFound)?;

        log_info!("Audio stream found: index={}, duration={}ms", stream.index(), format_ctx.duration() / 1000);

        let context = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters())?;
        let decoder_ctx = context.decoder().audio()?;

        log_info!("Decoder context created: format={:?}, rate={}, channels={}, channel_layout={}", 
            decoder_ctx.format(), decoder_ctx.rate(), decoder_ctx.channels(), decoder_ctx.channel_layout().bits());
        log_info!("Resampler will be lazily initialized on first decoded frame");

        Ok(Self {
            format_ctx: Some(format_ctx),
            decoder_ctx,
            resampler: None,
            target_sample_rate,
            target_channels,
            avio_ctx: std::ptr::null_mut(),
            io_ctx: std::ptr::null_mut(),
        })
    }

    pub fn new_from_fd(fd: i32, target_sample_rate: u32, target_channels: u16) -> Result<Self, ffmpeg_next::Error> {
        log_info!("=== FFmpegDecoder::new_from_fd START ===");
        log_info!("fd={}, target_sample_rate={}, target_channels={}", fd, target_sample_rate, target_channels);
        
        let (format_ctx, avio_ctx, io_ctx) = open_format_ctx_from_fd(fd)?;

        let stream = format_ctx.streams().best(ffmpeg_next::media::Type::Audio)
            .ok_or(ffmpeg_next::Error::StreamNotFound)?;

        log_info!("Audio stream found: index={}, duration={}ms", stream.index(), format_ctx.duration() / 1000);

        let context = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters())?;
        let decoder_ctx = context.decoder().audio()?;

        log_info!("Decoder context created: format={:?}, rate={}, channels={}, channel_layout={}", 
            decoder_ctx.format(), decoder_ctx.rate(), decoder_ctx.channels(), decoder_ctx.channel_layout().bits());
        log_info!("Resampler will be lazily initialized on first decoded frame");

        Ok(Self {
            format_ctx: Some(format_ctx),
            decoder_ctx,
            resampler: None,
            target_sample_rate,
            target_channels,
            avio_ctx,
            io_ctx
        })
    }

    pub fn extract_metadata(&self) -> TrackMetadata {
        let format_ctx = self.format_ctx.as_ref().unwrap();
        let duration_ms = format_ctx.duration() as u64 / 1000;
        
        TrackMetadata {
            duration_ms,
            sample_rate: self.target_sample_rate,
            channels: self.target_channels,
            ..Default::default()
        }
    }

    pub fn seek_to_ms(&mut self, position_ms: u64) -> Result<(), ffmpeg_next::Error> {
        let format_ctx = self.format_ctx.as_mut().unwrap();
        let ts_us = position_ms as i64 * 1000;
        unsafe {
            let ret = ffmpeg_next::ffi::avformat_seek_file(
                format_ctx.as_mut_ptr(),
                -1,
                i64::MIN,
                ts_us,
                i64::MAX,
                0,
            );
            if ret < 0 {
                return Err(ffmpeg_next::Error::from(ret));
            }
        }
        self.decoder_ctx.flush();
        Ok(())
    }

    pub fn decode_and_push(&mut self, producer: &mut HeapProd<f32>, cancel_flag: Arc<AtomicBool>) -> Result<(), ffmpeg_next::Error> {
        log_info!("=== decode_and_push START ===");
        log_info!("target_sample_rate={}, target_channels={}", self.target_sample_rate, self.target_channels);
        
        let format_ctx = self.format_ctx.as_mut().unwrap();
        
        let stream_index = match format_ctx.streams().best(ffmpeg_next::media::Type::Audio) {
            Some(s) => {
                log_info!("Found audio stream: index={}", s.index());
                s.index()
            },
            None => {
                log_error!("No audio stream found!");
                return Err(ffmpeg_next::Error::StreamNotFound);
            }
        };
        
        // 打印解码器信息
        log_info!("Decoder context: format={:?}, rate={}, channels={}, channel_layout={}", 
            self.decoder_ctx.format(), self.decoder_ctx.rate(), self.decoder_ctx.channels(), self.decoder_ctx.channel_layout().bits());
        
        let mut packets = format_ctx.packets();
        let mut packet_count: u64 = 0;
        let mut decoded_frame_count: u64 = 0;
        let mut skipped_packet_count: u64 = 0;
        let mut resample_error_count: u64 = 0;
        let mut total_samples_pushed: u64 = 0;

        log_info!("Starting packet loop...");

        while let Some((stream, packet)) = packets.next() {
            if cancel_flag.load(Ordering::Relaxed) { 
                log_info!("Cancel flag detected, breaking packet loop");
                break; 
            }
            
            packet_count += 1;
            
            if stream.index() == stream_index {
                if packet_count <= 10 || packet_count % 100 == 0 {
                    log_debug!("Packet #{}: size={} pts={:?}", packet_count, packet.size(), packet.pts());
                }
                
                if self.decoder_ctx.send_packet(&packet).is_err() {
                    skipped_packet_count += 1;
                    if skipped_packet_count <= 10 || skipped_packet_count % 50 == 0 {
                        log_warn!("send_packet failed for packet #{} (total skipped: {})", packet_count, skipped_packet_count);
                    }
                    continue;
                }

                let mut decoded = ffmpeg_next::frame::Audio::empty();
                
                while self.decoder_ctx.receive_frame(&mut decoded).is_ok() {
                    if cancel_flag.load(Ordering::Relaxed) { 
                        log_info!("Cancel flag detected in receive_frame loop");
                        return Ok(()); 
                    }

                    decoded_frame_count += 1;
                    
                    if decoded_frame_count <= 5 || decoded_frame_count % 100 == 0 {
                        log_info!("Decoded frame #{}: format={:?}, rate={}, channels={}, samples={}, channel_layout={}",
                            decoded_frame_count, decoded.format(), decoded.rate(), decoded.channels(), decoded.samples(), decoded.channel_layout().bits());
                    }

                    if self.resampler.is_none() {
                        log_info!("Initializing resampler (lazy init on first frame)...");
                        
                        let mut source_layout = decoded.channel_layout();
                        let channels = decoded.channels() as i32;
                        let rate = decoded.rate();

                        log_info!("Source frame info: format={:?}, rate={}, channels={}, layout={}", 
                            decoded.format(), rate, channels, source_layout.bits());

                        if source_layout.is_empty() {
                            source_layout = ffmpeg_next::channel_layout::ChannelLayout::default(if channels > 0 { channels } else { 2 });
                            log_info!("Source layout was empty, generated default: {}", source_layout.bits());
                        }
                        
                        let actual_rate = if rate > 0 { rate } else { 
                            log_warn!("Source rate was 0, using target rate: {}", self.target_sample_rate);
                            self.target_sample_rate 
                        };
                        let target_layout = ffmpeg_next::channel_layout::ChannelLayout::default(self.target_channels as i32);
                        
                        log_info!("Resampler params: src_format={:?}, src_layout={}, src_rate={}, dst_format=F32Packed, dst_layout={}, dst_rate={}",
                            decoded.format(), source_layout.bits(), actual_rate, target_layout.bits(), self.target_sample_rate);
                        
                        match ResamplerContext::get(
                            decoded.format(),
                            source_layout,
                            actual_rate,
                            ffmpeg_next::format::Sample::F32(ffmpeg_next::format::sample::Type::Packed),
                            target_layout,
                            self.target_sample_rate,
                        ) {
                            Ok(r) => {
                                log_info!("Resampler initialized successfully!");
                                self.resampler = Some(r);
                            },
                            Err(e) => {
                                log_error!("Resampler initialization failed: {:?}", e);
                                continue;
                            }
                        }
                    }

                    if let Some(resampler) = self.resampler.as_mut() {
                        let mut resampled = ffmpeg_next::frame::Audio::empty();
                        
                        if resampler.run(&decoded, &mut resampled).is_err() {
                            resample_error_count += 1;
                            log_warn!("resampler.run failed for frame #{} (total errors: {})", decoded_frame_count, resample_error_count);
                            continue;
                        }
                        
                        let nb_samples = resampled.samples();
                        let channels = resampled.channels();
                        let total_samples = nb_samples * channels as usize;
                        
                        if decoded_frame_count <= 5 || decoded_frame_count % 100 == 0 {
                            log_info!("Resampled frame #{}: samples={}, channels={}, total={}", 
                                decoded_frame_count, nb_samples, channels, total_samples);
                        }
                        
                        if total_samples > 0 {
                            let data = unsafe {
                                std::slice::from_raw_parts((*resampled.as_ptr()).data[0] as *const f32, total_samples)
                            };
                            
                            let mut remaining = data;
                            let mut push_attempts = 0;
                            while !remaining.is_empty() {
                                if cancel_flag.load(Ordering::Relaxed) { 
                                    log_info!("Cancel flag detected during push");
                                    return Ok(()); 
                                }
                                let pushed = producer.push_slice(remaining);
                                total_samples_pushed += pushed as u64;
                                remaining = &remaining[pushed..];
                                push_attempts += 1;
                                if !remaining.is_empty() {
                                    // Ring buffer full: park until the consumer
                                    // unparks us instead of a fixed 5 ms poll
                                    // (task 5). The timeout only guards a missed
                                    // wake; park consumes a pre-park unpark token.
                                    std::thread::park_timeout(
                                        std::time::Duration::from_millis(100),
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
        
        log_info!("Packet loop ended. packets={}, decoded_frames={}, skipped={}, resample_errors={}, samples_pushed={}", 
            packet_count, decoded_frame_count, skipped_packet_count, resample_error_count, total_samples_pushed);
        
        if cancel_flag.load(Ordering::Relaxed) { 
            log_info!("Cancel flag detected before EOF processing");
            return Ok(()); 
        }
        
        log_info!("Sending EOF to decoder...");
        let _ = self.decoder_ctx.send_eof();
        
        let mut decoded = ffmpeg_next::frame::Audio::empty();
        let mut eof_frame_count = 0;
        while self.decoder_ctx.receive_frame(&mut decoded).is_ok() {
            eof_frame_count += 1;
            log_info!("EOF frame #{}: format={:?}, rate={}, channels={}, samples={}", 
                eof_frame_count, decoded.format(), decoded.rate(), decoded.channels(), decoded.samples());
            
            if let Some(resampler) = self.resampler.as_mut() {
                let mut resampled = ffmpeg_next::frame::Audio::empty();
                if resampler.run(&decoded, &mut resampled).is_ok() {
                    let nb_samples = resampled.samples();
                    let channels = resampled.channels();
                    let total_samples = nb_samples * channels as usize;
                    
                    log_info!("EOF resampled frame #{}: samples={}, channels={}, total={}", eof_frame_count, nb_samples, channels, total_samples);
                    
                    if total_samples > 0 {
                        let data = unsafe {
                            std::slice::from_raw_parts((*resampled.as_ptr()).data[0] as *const f32, total_samples)
                        };
                        
                        let mut remaining = data;
                        while !remaining.is_empty() {
                            if cancel_flag.load(Ordering::Relaxed) { 
                                log_info!("Cancel flag detected during EOF push");
                                return Ok(()); 
                            }
                            let pushed = producer.push_slice(remaining);
                            total_samples_pushed += pushed as u64;
                            remaining = &remaining[pushed..];
                            if !remaining.is_empty() {
                                std::thread::yield_now();
                            }
                        }
                    }
                } else {
                    log_warn!("resampler.run failed for EOF frame #{}", eof_frame_count);
                }
            } else {
                log_warn!("No resampler available for EOF frame #{}", eof_frame_count);
            }
        }

        log_info!("=== decode_and_push END === packets={}, decoded_frames={}, eof_frames={}, skipped={}, resample_errors={}, total_samples_pushed={}", 
            packet_count, decoded_frame_count, eof_frame_count, skipped_packet_count, resample_error_count, total_samples_pushed);
        
        Ok(())
    }
}

