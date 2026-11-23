#ifndef FOUNDATION_AUDIO_H
#define FOUNDATION_AUDIO_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct fa_engine fa_engine;
typedef struct {
    uint64_t position_ms, duration_ms;
    uint32_t paused;
    float speed;
    uint64_t generation;
} fa_playback_status;
uint32_t fa_abi_version(void);
/* A headless engine is driven by fa_engine_render, at 48kHz stereo float32.
 * Serialize manual render with control calls. Device-backed engines render
 * automatically: never call fa_engine_render on them. Destroy only after all
 * application callers have stopped using the engine. */
fa_engine *fa_engine_create(bool headless);
void fa_engine_destroy(fa_engine *engine);
bool fa_engine_play(fa_engine *engine, const char *utf8_path);
/* Descriptors are duplicated; the caller retains ownership. Seekable readers
 * have independent logical cursors. The descriptor's current offset is the
 * start of the media object on Unix; reads leave that kernel offset unchanged.
 * Windows CRT descriptors address whole files starting at byte zero. */
bool fa_engine_play_fd(fa_engine *engine, int fd);
bool fa_engine_play_stream(fa_engine *engine, int fd, bool paused);
void fa_engine_pause(fa_engine *engine, bool paused);
bool fa_engine_stop(fa_engine *engine);
bool fa_engine_seek(fa_engine *engine, int64_t milliseconds);
void fa_engine_render(fa_engine *engine, float *stereo, size_t frames);
void fa_engine_status(fa_engine *engine, fa_playback_status *output);
/* Read-only stereo output energy. Capture allocates nothing and never changes PCM. */
typedef struct { float low, mid, high; uint32_t sequence; } fa_spectrum_power;
bool fa_engine_spectrum(fa_engine *engine, fa_spectrum_power *output);
bool fa_engine_add_path(fa_engine *engine, const char *utf8_path);
bool fa_engine_add_fd(fa_engine *engine, int fd);
bool fa_engine_clear_queue(fa_engine *engine);
bool fa_engine_select(fa_engine *engine, int32_t index);
int32_t fa_engine_queue_index(fa_engine *engine);
bool fa_engine_move(fa_engine *engine, int32_t from, int32_t to);
bool fa_engine_next(fa_engine *engine, bool previous);
/* 0: once, 1: list loop, 2: single loop, 3: shuffle */
bool fa_engine_mode(fa_engine *engine, uint32_t mode);
bool fa_engine_crossfade(fa_engine *engine, uint32_t milliseconds, bool soundstage);
bool fa_engine_replay_gain(fa_engine *engine, bool enabled);
/* NaN explicitly selects unity gain, suppressing embedded ReplayGain tags. */
bool fa_engine_queue_gain(fa_engine *engine, int32_t index, float db);
bool fa_engine_silence(fa_engine *engine, bool enabled, float threshold_dbfs, uint32_t minimum_ms);
float fa_engine_rms(fa_engine *engine);
/* Last failure, or an empty static string. No allocation or ownership transfer. */
const char *fa_engine_error(fa_engine *engine);
#ifdef __cplusplus
}
#endif
#endif
