import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Android native build for foundation.audio.
 *
 * FFmpeg is compiled with the
 * Linux NDK in a WSL-native ext4 workspace. Only the installed FFmpeg archives
 * are copied back to the checkout for Zig to link libjni_bridge.so.
 */

val androidApiLevel = 26
val androidNdkVersion = "29.0.14206865"
val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")

fun localProperty(key: String): String? {
    val properties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use(::load)
    }
    return (findProperty(key) as? String) ?: properties.getProperty(key)
}

val wslDistro = localProperty("ffmpeg.wsl.distro") ?: ""
val configuredWslWorkRoot =
    localProperty("ffmpeg.wsl.workRoot") ?: "~/.cache/foundation-audio"

val wslWorkRoot = if (isWindowsHost) {
    when {
        configuredWslWorkRoot == "~" || configuredWslWorkRoot.startsWith("~/") -> {
            val command = buildList {
                add("wsl.exe")
                if (wslDistro.isNotEmpty()) {
                    add("-d")
                    add(wslDistro)
                }
                add("--cd")
                add("~")
                add("--")
                add("pwd")
            }
            val home = providers.exec {
                commandLine(command)
                workingDir = File("C:\\")
            }.standardOutput.asText.get().trim()
            require(home.startsWith("/")) {
                "Could not resolve the WSL home directory (got '$home')"
            }
            if (configuredWslWorkRoot == "~") {
                home
            } else {
                "$home/${configuredWslWorkRoot.removePrefix("~/")}"
            }
        }
        configuredWslWorkRoot.startsWith("/") -> configuredWslWorkRoot
        else -> error(
            "ffmpeg.wsl.workRoot must be an absolute WSL path or start with ~/: " +
                configuredWslWorkRoot,
        )
    }
} else {
    configuredWslWorkRoot
}

fun windowsToWsl(file: File): String =
    Regex("^([A-Za-z]):[\\\\/]").replace(file.absolutePath) { match ->
        "/mnt/${match.groupValues[1].lowercase()}/"
    }.replace('\\', '/')

fun linuxSdkPath(): String {
    if (isWindowsHost) {
        return localProperty("ffmpeg.wsl.sdkDir") ?: error(
            "Set ffmpeg.wsl.sdkDir to the Linux Android SDK inside WSL.",
        )
    }
    return localProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: error("Android SDK not found")
}

fun hostSdkPath(): File {
    val configured = localProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: error("Set sdk.dir in local.properties")
    return File(configured)
}

abstract class WslScriptTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val script: Property<String>

    @get:Input
    abstract val distro: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun runScript() {
        outputDirectory.get().asFile.mkdirs()
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            val command = buildList {
                add("wsl.exe")
                if (distro.get().isNotEmpty()) {
                    add("-d")
                    add(distro.get())
                }
                add("--cd")
                add("~")
                add("--")
                add("bash")
                add("-s")
            }
            execOperations.exec {
                commandLine(command)
                workingDir = File("C:\\")
                standardInput = script.get().byteInputStream()
            }
        } else {
            execOperations.exec {
                commandLine("bash", "-s")
                standardInput = script.get().byteInputStream()
            }
        }
    }
}

data class FfmpegAbi(
    val abi: String,
    val arch: String,
    val cpu: String,
    val compilerPrefix: String,
    val rustTarget: String,
    val extraCFlags: String? = null,
    val extraLdFlags: String? = null,
)

val ffmpegAbis = listOf(
    FfmpegAbi(
        abi = "arm64-v8a",
        arch = "aarch64",
        cpu = "armv8-a",
        compilerPrefix = "aarch64-linux-android",
        rustTarget = "aarch64-linux-android",
    ),
    FfmpegAbi(
        abi = "armeabi-v7a",
        arch = "arm",
        cpu = "armv7-a",
        compilerPrefix = "armv7a-linux-androideabi",
        rustTarget = "armv7-linux-androideabi",
        extraCFlags = "-march=armv7-a -mfloat-abi=softfp",
        extraLdFlags = "-Wl,--fix-cortex-a8",
    ),
    FfmpegAbi(
        abi = "x86_64",
        arch = "x86_64",
        cpu = "x86-64",
        compilerPrefix = "x86_64-linux-android",
        rustTarget = "x86_64-linux-android",
    ),
)

val enabledDecoders = listOf(
    "aac", "aac_fixed", "ac3", "eac3", "alac", "flac", "mp3", "mp3float",
    "vorbis", "opus", "wmav1", "wmav2", "wmapro", "wmalossless", "ape",
    "wavpack", "tta", "tak", "dsd_lsbf", "dsd_lsbf_planar", "dsd_msbf",
    "dsd_msbf_planar", "adpcm_ima_wav", "adpcm_ima_qt", "adpcm_ms",
    "pcm_alaw", "pcm_mulaw", "pcm_f16le", "pcm_f24le", "pcm_f32be",
    "pcm_f32le", "pcm_f64be", "pcm_f64le", "pcm_s8", "pcm_s16be",
    "pcm_s16le", "pcm_s24be", "pcm_s24le", "pcm_s32be", "pcm_s32le",
    "pcm_s64be", "pcm_s64le", "pcm_u8", "pcm_u16be", "pcm_u16le",
    "pcm_u24be", "pcm_u24le", "pcm_u32be", "pcm_u32le", "qdm2", "qdmc",
    "mace3", "mace6",
)

val commonConfigureFlags = listOf(
    "--target-os=android",
    "--disable-static",
    "--enable-shared",
    "--disable-gpl",
    "--disable-nonfree",
    "--disable-version3",
    "--disable-autodetect",
    "--enable-zlib",
    "--disable-doc",
    "--disable-programs",
    "--disable-everything",
    "--disable-avdevice",
    "--enable-avformat",
    "--enable-avcodec",
    "--enable-swresample",
    "--disable-swscale",
    "--disable-avfilter",
    "--disable-symver",
    "--extra-ldexeflags=-pie",
    "--disable-v4l2-m2m",
    "--disable-vulkan",
    "--disable-x86asm",
    "--enable-demuxer=flac",
    "--enable-demuxer=mp3",
    "--enable-demuxer=mov",
    "--enable-demuxer=aac",
    "--enable-demuxer=ogg",
    "--enable-demuxer=wav",
    "--enable-demuxer=aiff",
    "--enable-demuxer=asf",
    "--enable-demuxer=ape",
    "--enable-demuxer=caf",
    "--enable-demuxer=dsf",
    "--enable-demuxer=iff",
    "--enable-demuxer=tak",
    "--enable-demuxer=tta",
    "--enable-demuxer=wv",
    "--enable-demuxer=matroska",
    "--enable-parser=aac",
    "--enable-parser=aac_latm",
    "--enable-parser=ac3",
    "--enable-parser=flac",
    "--enable-parser=mpegaudio",
    "--enable-parser=opus",
    "--enable-parser=tak",
    "--enable-parser=vorbis",
    "--enable-muxer=matroska",
    "--enable-bsf=aac_adtstoasc",
) + enabledDecoders.map { "--enable-decoder=$it" }

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

val ffmpegSource = rootProject.file("third_party/ffmpeg")
val ffmpegCommit = providers.exec {
    commandLine("git", "-C", ffmpegSource.absolutePath, "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

val ffmpegOutput = layout.projectDirectory.dir("ffmpeg-android-shared")
val jniOutput = layout.projectDirectory.dir("jniLibs")
val markerRoot = layout.buildDirectory.dir("wsl-markers")
val wslSource = "$wslWorkRoot/ffmpeg"
val wslBuildRoot = "$wslWorkRoot/build-shared"
val wslInstallRoot = "$wslWorkRoot/install-shared"
val linuxNdk = "${linuxSdkPath()}/ndk/$androidNdkVersion"
val linuxToolchain = "$linuxNdk/toolchains/llvm/prebuilt/linux-x86_64/bin"

val syncFfmpegWslSource = tasks.register<WslScriptTask>("syncFfmpegWslSource") {
    group = "build"
    description = "Sync the pinned FFmpeg submodule commit into the WSL ext4 workspace"
    distro.set(wslDistro)
    outputDirectory.set(markerRoot.map { it.dir("ffmpeg-source") })
    script.set(ffmpegCommit.map { commit ->
        val origin = if (isWindowsHost) windowsToWsl(ffmpegSource) else ffmpegSource.absolutePath
        val marker = if (isWindowsHost) {
            windowsToWsl(markerRoot.get().dir("ffmpeg-source").asFile)
        } else {
            markerRoot.get().dir("ffmpeg-source").asFile.absolutePath
        }
        """
        set -e
        mkdir -p ${shellQuote(wslWorkRoot)}
        if [ ! -d ${shellQuote("$wslSource/.git")} ]; then
            git clone --no-checkout ${shellQuote(origin)} ${shellQuote(wslSource)}
        fi
        git -C ${shellQuote(wslSource)} fetch origin ${shellQuote(commit)}
        git -C ${shellQuote(wslSource)} checkout --force ${shellQuote(commit)}
        mkdir -p ${shellQuote(marker)}
        printf '%s\n' ${shellQuote(commit)} > ${shellQuote("$marker/commit")}
        """.trimIndent()
    })
}

val ffmpegBuildTasks = ffmpegAbis.map { target ->
    tasks.register<WslScriptTask>(
        "buildFfmpeg" + target.abi.split('-', '_').joinToString("") {
            it.replaceFirstChar(Char::uppercase)
        },
    ) {
        group = "build"
        description = "Build FFmpeg for ${target.abi} in the WSL ext4 workspace"
        dependsOn(syncFfmpegWslSource)
        distro.set(wslDistro)
        outputDirectory.set(ffmpegOutput.dir(target.abi))

        val windowsInstall = if (isWindowsHost) {
            windowsToWsl(ffmpegOutput.dir(target.abi).asFile)
        } else {
            ffmpegOutput.dir(target.abi).asFile.absolutePath
        }
        val flags = commonConfigureFlags.joinToString("\n") { "    ${shellQuote(it)}" }
        val optionalFlags = buildString {
            target.extraCFlags?.let {
                append("\n    ")
                append(shellQuote("--extra-cflags=$it"))
            }
            append("\n    ")
            append(shellQuote("--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 ${target.extraLdFlags ?: ""}"))
        }
        script.set(
            """
            set -e
            build_dir=${shellQuote("$wslBuildRoot/${target.abi}")}
            install_dir=${shellQuote("$wslInstallRoot/${target.abi}")}
            mkdir -p "${'$'}build_dir" "${'$'}install_dir"
            cd "${'$'}build_dir"
            configure_flags=(
            $flags
                ${shellQuote("--prefix=$wslInstallRoot/${target.abi}")}
                ${shellQuote("--libdir=$wslInstallRoot/${target.abi}/lib")}
                ${shellQuote("--incdir=$wslInstallRoot/${target.abi}/include")}
                ${shellQuote("--arch=${target.arch}")}
                ${shellQuote("--cpu=${target.cpu}")}
                ${shellQuote("--cross-prefix=$linuxToolchain/${target.compilerPrefix}$androidApiLevel-")}
                ${shellQuote("--nm=$linuxToolchain/llvm-nm")}
                ${shellQuote("--ar=$linuxToolchain/llvm-ar")}
                ${shellQuote("--ranlib=$linuxToolchain/llvm-ranlib")}
                ${shellQuote("--strip=$linuxToolchain/llvm-strip")}
                ${shellQuote("--cc=$linuxToolchain/${target.compilerPrefix}$androidApiLevel-clang")}
                ${shellQuote("--cxx=$linuxToolchain/${target.compilerPrefix}$androidApiLevel-clang++")}
                '--host-cc=/usr/bin/cc'
                '--enable-cross-compile'
                ${shellQuote("--sysroot=$linuxNdk/toolchains/llvm/prebuilt/linux-x86_64/sysroot")}$optionalFlags
            )
            ${shellQuote("$wslSource/configure")} "${'$'}{configure_flags[@]}"
            make -j"${'$'}(nproc)"
            make install
            test -f "${'$'}install_dir/lib/libavformat.so"
            grep -q '^CONFIG_MATROSKA_DEMUXER=yes' ffbuild/config.mak
            grep -q '^CONFIG_SHARED=yes' ffbuild/config.mak
            if grep -Eq '^CONFIG_(GPL|NONFREE|VERSION3|STATIC)=yes' ffbuild/config.mak; then
                echo 'Refusing a GPL/nonfree/version3/static FFmpeg build' >&2
                exit 1
            fi
            mkdir -p "${'$'}install_dir/licenses"
            cp ${shellQuote("$wslSource/COPYING.LGPLv2.1")} "${'$'}install_dir/licenses/"
            cp ${shellQuote("$wslSource/LICENSE.md")} "${'$'}install_dir/licenses/"
            cp ffbuild/config.mak "${'$'}install_dir/licenses/config-${target.abi}.mak"
            printf '%s\n' "${'$'}{configure_flags[@]}" > "${'$'}install_dir/licenses/configure-${target.abi}.txt"
            git -C ${shellQuote(wslSource)} rev-parse HEAD > "${'$'}install_dir/licenses/source-commit.txt"
            git -C ${shellQuote(wslSource)} diff --binary HEAD > "${'$'}install_dir/licenses/changes.diff"
            mkdir -p ${shellQuote(windowsInstall)}
            rsync -a --delete "${'$'}install_dir/" ${shellQuote("$windowsInstall/")}
            """.trimIndent(),
        )
    }
}

val buildFfmpegAndroid = tasks.register("buildFfmpegAndroid") {
    group = "build"
    description = "Build LGPL FFmpeg shared libraries for every supported Android ABI"
    dependsOn(ffmpegBuildTasks)
}
