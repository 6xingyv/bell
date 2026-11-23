use engine::PlaybackEngine;
use std::path::PathBuf;
use std::process::ExitCode;
use std::thread;
use std::time::Duration;

fn main() -> ExitCode {
    let files: Vec<PathBuf> = std::env::args().skip(1).map(PathBuf::from).collect();
    let Some(first) = files.first() else {
        eprintln!("Usage: cli-player <file1> [file2 ...]");
        return ExitCode::FAILURE;
    };

    let engine = PlaybackEngine::new();
    // Keep the CLI aligned with the public playback contract: `play_path`
    // replaces the queue and starts playback, while subsequent files are
    // appended with `add_to_queue_path`.
    if !engine.play_path(first.to_string_lossy().into_owned()) {
        eprintln!("Failed to start playback: {}", first.display());
        return ExitCode::FAILURE;
    }

    for file in files.iter().skip(1) {
        if !engine.add_to_queue_path(file.to_string_lossy().into_owned()) {
            eprintln!("Failed to add to queue: {}", file.display());
        }
    }

    // The engine advances the queue and changes to Stopped when the final
    // track drains. Do not impose an arbitrary one-hour playback limit.
    while engine.is_playing() {
        thread::sleep(Duration::from_millis(100));
    }

    ExitCode::SUCCESS
}
