#undef _FORTIFY_SOURCE
#define _FORTIFY_SOURCE 0
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <stdio.h>
#include "device.h"
#ifdef __ANDROID__
#include <jni.h>
#endif
