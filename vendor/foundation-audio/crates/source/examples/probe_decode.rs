use ringbuf::HeapRb;
use ringbuf::traits::{Consumer, Split};
use source::AudioSource;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: probe_decode <audio-file>");
    let scanned = source::probe_path(&path).expect("probe metadata without decoder");
    let scanned_cover = scanned.cover_art.as_deref().unwrap_or_default();
    println!(
        "scan title={:?} codec_params={}Hz/{}ch cover={} bytes magic={:02x?}",
        scanned.title,
        scanned.sample_rate,
        scanned.channels,
        scanned_cover.len(),
        scanned_cover.get(..2).unwrap_or_default(),
    );

    let (mut source, metadata) = AudioSource::open_path(&path).expect("open audio source");
    let cover = metadata.cover_art.as_deref().unwrap_or_default();
    println!(
        "metadata title={:?} codec_params={}Hz/{}ch cover={} bytes magic={:02x?}",
        metadata.title,
        metadata.sample_rate,
        metadata.channels,
        cover.len(),
        cover.get(..2).unwrap_or_default(),
    );

    source.set_targets(48_000, 2);
    let (mut producer, mut consumer) = HeapRb::<f32>::new(48_000).split();
    let cancel = Arc::new(AtomicBool::new(false));
    let decode_cancel = Arc::clone(&cancel);
    let decoder = std::thread::spawn(move || source.decode_and_push(&mut producer, decode_cancel));

    let deadline = Instant::now() + Duration::from_secs(10);
    let mut decoded_samples = 0usize;
    let mut scratch = [0.0f32; 4096];
    while decoded_samples < 48_000 && Instant::now() < deadline {
        let read = consumer.pop_slice(&mut scratch);
        decoded_samples += read;
        if read == 0 {
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    cancel.store(true, Ordering::Relaxed);
    decoder
        .join()
        .expect("decoder thread")
        .expect("decode audio");
    assert!(decoded_samples > 0, "decoder produced no PCM samples");
    println!("decoded_pcm_samples={decoded_samples}");
}
