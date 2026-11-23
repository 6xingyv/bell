# foundation.audio

Zig 音频引擎迁移分支，使用 **Zig 0.17.0**。解码和重采样直接调用独立动态链接的
FFmpeg；播放队列、DSP、响度分析、投送与 JNI 桥接由 Zig 实现。
原 Rust 文件保留作迁移参考，默认构建不再使用它们。AI 功能暂时关闭。

## 签出

```sh
git clone https://github.com/6xingyv/foundation.audio audio
cd audio
# third_party/ffmpeg 是 FFmpeg release/8.1 子模块，必须初始化
git submodule update --init third_party/ffmpeg
```

## 构建

Windows 可运行 `pwsh tools/setup-zig.ps1` 安装校验过 SHA-256 的固定版本。

Android 构建由 Gradle 调用 Zig，Windows 可以原生构建三个 ABI 的 JNI 库。
FFmpeg 作为单独的共享库依赖缓存，不会随每次音频库修改重新编译。

`local.properties`（不提交）：

```properties
sdk.dir=C\:\\Environments\\Android\\SDK
ffmpeg.wsl.sdkDir=/home/simon/Android/Sdk
ffmpeg.wsl.distro=Ubuntu
ffmpeg.wsl.workRoot=~/.cache/foundation-audio
```

首次准备 LGPL FFmpeg 动态库，或主动更新依赖时：

```powershell
.\gradlew.bat buildAndroidNative -PrebuildFfmpeg
```

这一步沿用 WSL/Linux NDK 构建 FFmpeg。普通修改只需：

```powershell
.\gradlew.bat buildAndroidNative
```

产物为 `jniLibs/<abi>/libjni_bridge.so` 和四个独立的 FFmpeg `.so`。
应用必须一起打包这些库，以及 `build/generated/audioAssets` 中的许可证资源。
`zig.executable` / `ZIG_EXE` 可指定编译器；版本必须与 `.zig-version` 一致。

Windows 桌面依赖与测试：

```powershell
.\tools\build-ffmpeg-windows.ps1
$env:PATH = "$PWD\ffmpeg-windows-shared\bin;" + $env:PATH
.\tools\zig.ps1 build test integration '-Dffmpeg=ffmpeg-windows-shared' '-Doptimize=ReleaseSafe'
```

Windows FFmpeg 依赖构建使用 MSYS2 的 make/binutils 和 Zig CC；具体前提见脚本。
桌面 C ABI 见 `zig/include/foundation_audio.h`。

发布准备：

```powershell
.\gradlew.bat packageAudioDistribution
python tools/verify-native.py --ndk C:/Environments/Android/SDK/ndk/29.0.14206865
```

分发包包含动态库、FFmpeg 对应源码、编译参数和许可证。默认禁用 GPL、nonfree、
version3 组件，拒绝静态回退。动态链接不能替代其余分发义务，详见
[FFmpeg 分发说明](zig/FFMPEG-NOTICE.txt)。

## 迁移边界

JNI 导出与数据格式保留，Rust 源码级 API 不再兼容。配套 clef 分支
`rewrite/zig-audio` 的 `compileAudioZig` 调用本仓库构建，并打包全部动态库和许可证。
旧 `compileRustAndroid` 任务名仅作为兼容别名；推荐模块自己的 Rust 构建保持独立。
该分支尚未经 Android 真机和多设备投送验收。实现对应关系、测试范围及待验收项见
[迁移说明](zig/MIGRATION.txt)。
