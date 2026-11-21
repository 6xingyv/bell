#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#define _LARGEFILE64_SOURCE
/* Device backend only; all playback, queue, decoding and DSP live in Zig.
 * miniaudio 0.11.25 / 9634bedb5b5a2ca38c1ee7108a9358a4e233f14d.
 * Disable codecs: FFmpeg owns decoding and resampling. */
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#define MINIAUDIO_IMPLEMENTATION
#include "vendor/miniaudio.h""
#include "include/device.h"
#include <stdlib.h>
#include <stdio.h>
#include <sys/stat.h>
typedef struct { ma_device device; fa_render_fn render; void *user; } fa_device;
static void callback(ma_device *device, void *output, const void *input, ma_uint32 frames) {
    (void)input;
    fa_device *self = (fa_device *)device->pUserData;
    self->render(self->user, (float *)output, frames);
}
void *fa_device_open(fa_render_fn render, void *user, uint32_t sample_rate) {
    fa_device *self = calloc(1, sizeof(*self));
    if (!self) return NULL;
    self->render = render; self->user = user;
    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_f32;
    config.playback.channels = 2;
    config.sampleRate = sample_rate;
    config.dataCallback = callback;
    config.pUserData = self;
    if (ma_device_init(NULL, &config, &self->device) != MA_SUCCESS) { free(self); return NULL; }
    return self;
}
int fa_device_start(void *device) { return ma_device_start(&((fa_device *)device)->device); }
void fa_device_stop(void *device) { if (device) ma_device_stop(&((fa_device *)device)->device); }
void fa_device_close(void *device) {
    if (!device) return;
    ma_device_uninit(&((fa_device *)device)->device);
    free(device);
}
void fa_sleep_ms(uint32_t milliseconds) { ma_sleep(milliseconds); }
int64_t fa_device_output_delay_ns(void *opaque) {
#ifdef MA_SUPPORT_AAUDIO
    if (!opaque) return -1;
    fa_device *self = opaque;
    ma_device *d = &self->device;
    if (d->pContext->backend != ma_backend_aaudio) return -1;
    typedef int32_t (*timestamp_fn)(void *, int32_t, int64_t *, int64_t *);
    typedef int64_t (*written_fn)(void *);
    timestamp_fn timestamp = (timestamp_fn)ma_dlsym(ma_device_get_log(d), d->pContext->aaudio.hAAudio, "AAudioStream_getTimestamp");
    written_fn written = (written_fn)ma_dlsym(ma_device_get_log(d), d->pContext->aaudio.hAAudio, "AAudioStream_getFramesWritten");
    if (!timestamp || !written) return -1;
    /* Protect the stream against the backend's asynchronous route replacement. */
    ma_mutex_lock(&d->aaudio.rerouteLock);
    void *stream = d->aaudio.pStreamPlayback;
    int64_t frame = 0, at = 0, result = -1;
    if (stream && timestamp(stream, 1 /* CLOCK_MONOTONIC */, &frame, &at) == 0) {
        int64_t count = written(stream);
        int64_t rate = d->playback.internalSampleRate;
        if (rate > 0 && count >= frame) {
            result = at + (count - frame) * 1000000000LL / rate - (int64_t)fa_monotonic_ns();
            if (result < 0) result = 0;
        }
    }
    ma_mutex_unlock(&d->aaudio.rerouteLock);
    return result;
#else
    (void)opaque;
    return -1;
#endif
}
/* Clang supplies ARMv7 LDREXD/STREXD; Zig's current ARM atomic type limit is
 * 32 bits even for CPUs that implement the doubleword instructions. */
uint64_t fa_atomic_load64(const uint64_t *p) { return __atomic_load_n(p, __ATOMIC_SEQ_CST); }
void fa_atomic_store64(uint64_t *p, uint64_t v) { __atomic_store_n(p, v, __ATOMIC_SEQ_CST); }
uint64_t fa_atomic_swap64(uint64_t *p, uint64_t v) { return __atomic_exchange_n(p, v, __ATOMIC_SEQ_CST); }
uint64_t fa_atomic_add64(uint64_t *p, uint64_t v) { return __atomic_fetch_add(p, v, __ATOMIC_SEQ_CST); }
#if defined(_WIN32)
#include <io.h>
int64_t fa_fd_size(int fd) { struct _stat64 s; return _fstat64(fd, &s) ? -1 : s.st_size; }
int64_t fa_fd_position(int fd) { return _lseeki64(fd, 0, SEEK_CUR); }
int fa_read_at(int fd, void *p, int n, int64_t offset) {
    HANDLE handle = (HANDLE)_get_osfhandle(fd);
    OVERLAPPED position = {0}; DWORD read_count = 0;
    position.Offset = (DWORD)offset; position.OffsetHigh = (DWORD)((uint64_t)offset >> 32);
    if (!ReadFile(handle, p, (DWORD)n, &read_count, &position)) return GetLastError() == ERROR_HANDLE_EOF ? 0 : -1;
    return (int)read_count;
}
void *fa_fopen_utf8(const char *path) {
    int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (!n) return NULL;
    wchar_t *wide = malloc((size_t)n * sizeof(wchar_t));
    if (!wide) return NULL;
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, n);
    FILE *file = _wfopen(wide, L"rb"); free(wide); return file;
}
int fa_read_fd(int fd, void *p, int n, const uint32_t *epoch, uint32_t expected) {
    HANDLE handle = (HANDLE)_get_osfhandle(fd);
    if (GetFileType(handle) == FILE_TYPE_PIPE) {
        for (;;) {
            if (epoch && __atomic_load_n(epoch, __ATOMIC_ACQUIRE) != expected) return -1;
            DWORD available = 0;
            if (!PeekNamedPipe(handle, NULL, 0, NULL, &available, NULL))
                return GetLastError() == ERROR_BROKEN_PIPE ? 0 : -1;
            if (available) { if ((DWORD)n > available) n = (int)available; break; }
            ma_sleep(1);
        }
    }
    if (epoch && __atomic_load_n(epoch, __ATOMIC_ACQUIRE) != expected) return -1;
    return _read(fd, p, n);
}
int fa_write_fd(int fd, const void *p, int n, const uint32_t *epoch, uint32_t expected) {
    if (epoch && __atomic_load_n(epoch, __ATOMIC_ACQUIRE) != expected) return -1;
    return _write(fd, p, n);
}
uint64_t fa_monotonic_ns(void) {
    LARGE_INTEGER count, frequency;
    QueryPerformanceCounter(&count); QueryPerformanceFrequency(&frequency);
    return (uint64_t)(count.QuadPart / frequency.QuadPart) * 1000000000ULL +
        (uint64_t)(count.QuadPart % frequency.QuadPart) * 1000000000ULL / frequency.QuadPart;
}
#else
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <pthread.h>
#include <time.h>
#include <sys/socket.h>
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
int64_t fa_fd_size(int fd) {
#if defined(__APPLE__)
    struct stat s; return fstat(fd, &s) ? -1 : s.st_size;
#else
    struct stat64 s; return fstat64(fd, &s) ? -1 : s.st_size;
#endif
}
int64_t fa_fd_position(int fd) {
#if defined(__APPLE__)
    return lseek(fd, 0, SEEK_CUR);
#else
    return lseek64(fd, 0, SEEK_CUR);
#endif
}
int fa_read_at(int fd, void *p, int n, int64_t offset) {
    for (;;) {
#if defined(__APPLE__)
        int r = (int)pread(fd, p, (size_t)n, offset);
#else
        int r = (int)pread64(fd, p, (size_t)n, offset);
#endif
        if (r >= 0 || errno != EINTR) return r;
    }
}
void *fa_fopen_utf8(const char *path) { return fopen(path, "rb"); }
static int wait_fd(int fd, short events, const uint32_t *epoch, uint32_t expected) {
    struct pollfd p = {fd, events, 0};
    for (;;) {
        if (epoch && __atomic_load_n(epoch, __ATOMIC_ACQUIRE) != expected) return -1;
        int r = poll(&p, 1, 25);
        if (r > 0) return (p.revents & POLLNVAL) ? -1 : 0;
        if (r < 0 && errno != EINTR) return -1;
    }
}
int fa_read_fd(int fd, void *p, int n, const uint32_t *epoch, uint32_t expected) {
    for (;;) {
        if (wait_fd(fd, POLLIN, epoch, expected)) return -1;
        int r = (int)read(fd, p, n);
        if (r >= 0 || (errno != EINTR && errno != EAGAIN)) return r;
    }
}
int fa_write_fd(int fd, const void *p, int n, const uint32_t *epoch, uint32_t expected) {
    /* Casting runs on its own worker: block SIGPIPE for that thread so a
     * disconnected receiver is an I/O error instead of process termination. */
    sigset_t blocked; sigemptyset(&blocked); sigaddset(&blocked, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &blocked, NULL);
    for (;;) {
        if (wait_fd(fd, POLLOUT, epoch, expected)) return -1;
        int count = n > 4096 ? 4096 : n;
        int r = (int)send(fd, p, count, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (r < 0 && errno == ENOTSOCK) r = (int)write(fd, p, count);
        if (r >= 0 || (errno != EINTR && errno != EAGAIN)) return r;
    }
}
uint64_t fa_monotonic_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ULL + t.tv_nsec;
}
#endif
