#ifndef FOUNDATION_AUDIO_DEVICE_H
#define FOUNDATION_AUDIO_DEVICE_H
#include <stdint.h>
typedef void (*fa_render_fn)(void *user, float *samples, uint32_t frames);
void *fa_device_open(fa_render_fn render, void *user, uint32_t sample_rate);
int fa_device_start(void *device);
void fa_device_stop(void *device);
void fa_device_close(void *device);
/* Remaining output queue time, measured from the backend presentation timestamp.
 * Query from a worker thread. Returns -1 when no reliable timestamp is available. */
int64_t fa_device_output_delay_ns(void *device);
void fa_sleep_ms(uint32_t milliseconds);
uint64_t fa_atomic_load64(const uint64_t *value);
void fa_atomic_store64(uint64_t *value, uint64_t next);
uint64_t fa_atomic_swap64(uint64_t *value, uint64_t next);
uint64_t fa_atomic_add64(uint64_t *value, uint64_t next);
int fa_read_fd(int fd, void *buffer, int size, const uint32_t *epoch, uint32_t expected);
int fa_write_fd(int fd, const void *buffer, int size, const uint32_t *epoch, uint32_t expected);
uint64_t fa_monotonic_ns(void);
int64_t fa_fd_size(int fd);
int64_t fa_fd_position(int fd);
int fa_read_at(int fd, void *buffer, int size, int64_t offset);
void *fa_fopen_utf8(const char *path);
#endif
