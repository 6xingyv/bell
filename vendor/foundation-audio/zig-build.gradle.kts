import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

val zigVersion = file(".zig-version").readText().trim()
val localConfig = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use(::load)
}
fun config(name: String): String? = providers.gradleProperty(name).orNull ?: localConfig.getProperty(name)
val windows = System.getProperty("os.name").lowercase().contains("windows")
val defaultZig = if (windows) {
    File(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set"),
        "Programs/Zig/$zigVersion/zig-x86_64-windows-$zigVersion/zig.exe").absolutePath
} else "zig"
val zigExecutable = config("zig.executable") ?: System.getenv("ZIG_EXE") ?: defaultZig
val sdkPath = config("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val ndkPath = config("ndk.dir") ?: System.getenv("ANDROID_NDK_HOME")
    ?: sdkPath?.let { "$it/ndk/29.0.14206865" } ?: "missing-android-ndk"
val ffmpegPath = config("ffmpeg.android.dir") ?: "ffmpeg-android-shared"

abstract class ZigAudioTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input abstract val compiler: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val cpu: Property<String>
    @get:Input abstract val ndk: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpeg: DirectoryProperty
    @get:OutputFile abstract val library: RegularFileProperty
    @get:OutputFiles abstract val runtimeLibraries: ConfigurableFileCollection
    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:LocalState abstract val intermediate: DirectoryProperty
    @TaskAction fun compile() {
        val output = java.io.ByteArrayOutputStream()
        execOperations.exec { commandLine(compiler.get(), "version"); standardOutput = output }
        require(output.toString().trim() == version.get()) { "Expected Zig ${version.get()}, got $output" }
        val root = projectDirectory.get().asFile
        val configs = ffmpeg.get().dir("licenses").asFile.listFiles { f -> f.name.startsWith("config-") && f.extension == "mak" } ?: emptyArray()
        require(configs.isNotEmpty()) { "FFmpeg build provenance is missing (licenses/config-*.mak)" }
        for (file in configs) {
            val config = file.readText()
            require(Regex("(?m)^CONFIG_SHARED=yes$").containsMatchIn(config)) { "FFmpeg must be shared" }
            for (flag in listOf("GPL", "NONFREE", "VERSION3", "STATIC")) {
                require(Regex("(?m)^!CONFIG_${flag}=yes$").containsMatchIn(config)) { "FFmpeg $flag must be explicitly disabled" }
            }
        }
        require(File(ndk.get(), "source.properties").isFile) { "Android NDK missing: ${ndk.get()}" }
        for (name in listOf("avformat", "avcodec", "swresample", "avutil")) {
            require(ffmpeg.get().file("lib/lib$name.so").asFile.isFile) {
                "FFmpeg $name shared library missing. Provision LGPL shared FFmpeg or run with -PrebuildFfmpeg. Static archives are not accepted."
            }
        }
        execOperations.exec {
            workingDir = root
            commandLine(compiler.get(), "build", "-Dtarget=${target.get()}", "-Dcpu=${cpu.get()}",
                "-Dndk=${File(ndk.get()).absolutePath}", "-Dffmpeg=${ffmpeg.get().asFile.absolutePath}",
                "-Doptimize=ReleaseSafe", "-Dstrip=true", "--prefix", intermediate.get().asFile.absolutePath)
        }
        val built = intermediate.get().file("lib/libjni_bridge.so").asFile
        require(built.isFile) { "Zig did not produce $built" }
        val destination = library.get().asFile
        destination.parentFile.mkdirs()
        built.copyTo(destination, overwrite = true)
        for (name in listOf("avformat", "avcodec", "swresample", "avutil")) {
            ffmpeg.get().file("lib/lib$name.so").asFile.copyTo(File(destination.parentFile, "lib$name.so"), overwrite = true)
        }
    }
}
val abis = mapOf(
    "arm64-v8a" to Pair("aarch64-linux-android", "baseline"),
    "armeabi-v7a" to Pair("arm-linux-androideabi", "cortex_a7"),
    "x86_64" to Pair("x86_64-linux-android", "baseline"),
)
val nativeTasks = abis.map { (abi, platform) ->
    tasks.register<ZigAudioTask>("buildAudioZig" + abi.replace("-", "").replace("_", "")) {
        group = "build"
        description = "Compile foundation.audio with Zig for $abi"
        compiler.set(zigExecutable); version.set(zigVersion)
        target.set(platform.first); cpu.set(platform.second); ndk.set(ndkPath)
        projectDirectory.set(layout.projectDirectory)
        sources.from(fileTree(layout.projectDirectory) { include("build.zig", "build.zig.zon", ".zig-version", "zig/src/**", "zig/include/**", "zig/vendor/**", "zig/device.c", "zig/exports.map") })
        ffmpeg.set(layout.projectDirectory.dir("$ffmpegPath/$abi"))
        intermediate.set(layout.buildDirectory.dir("zig/$abi"))
        library.set(layout.projectDirectory.file("jniLibs/$abi/libjni_bridge.so"))
        runtimeLibraries.from(listOf("avformat", "avcodec", "swresample", "avutil").map { layout.projectDirectory.file("jniLibs/$abi/lib$it.so") })
        if (providers.gradleProperty("rebuildFfmpeg").isPresent) dependsOn("buildFfmpegAndroid")
    }
}
tasks.register("buildAudioJniAndroid") { group = "build"; dependsOn(nativeTasks) }
tasks.register("buildAndroidNative") { group = "build"; dependsOn("buildAudioJniAndroid") }
tasks.named("assemble") { dependsOn("buildAudioJniAndroid") }
tasks.named<Delete>("clean") { delete(layout.projectDirectory.dir("jniLibs")) }

val copyFfmpegNotices = tasks.register<Sync>("copyFfmpegNotices") {
    group = "build"
    dependsOn(nativeTasks)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    abis.keys.forEach { abi -> from(layout.projectDirectory.dir("$ffmpegPath/$abi/licenses")) }
    from(layout.projectDirectory.file("zig/FFMPEG-NOTICE.txt"))
    into(layout.buildDirectory.dir("generated/audioAssets/licenses/ffmpeg"))
}
tasks.named("buildAndroidNative") { dependsOn(copyFfmpegNotices) }

val ffmpegSourceArchive = tasks.register<Exec>("archiveFfmpegSource") {
    group = "distribution"
    dependsOn(copyFfmpegNotices)
    val output = layout.buildDirectory.file("ffmpeg-distribution/ffmpeg-source.tar.gz")
    val source = layout.projectDirectory.dir("third_party/ffmpeg")
    inputs.dir(layout.projectDirectory.dir("$ffmpegPath/arm64-v8a/licenses"))
    outputs.file(output)
    doFirst {
        val commit = layout.projectDirectory.file("$ffmpegPath/arm64-v8a/licenses/source-commit.txt").asFile.readText().trim()
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Missing exact FFmpeg source commit" }
        abis.keys.forEach { abi ->
            require(layout.projectDirectory.file("$ffmpegPath/$abi/licenses/source-commit.txt").asFile.readText().trim() == commit) { "FFmpeg ABI source revisions differ" }
            require(layout.projectDirectory.file("$ffmpegPath/$abi/licenses/changes.diff").asFile.readText().isBlank()) { "FFmpeg source has modifications: include the matching patch before packaging" }
        }
        output.get().asFile.parentFile.mkdirs()
        commandLine("git", "-C", source.asFile.absolutePath, "archive", "--format=tar.gz", "--prefix=ffmpeg/", "--output=${output.get().asFile.absolutePath}", commit)
    }
}
tasks.register<Zip>("packageAudioDistribution") {
    group = "distribution"
    description = "Package JNI, separate LGPL FFmpeg shared objects, notices and matching FFmpeg source"
    dependsOn(ffmpegSourceArchive)
    archiveFileName.set("foundation-audio-zig-android.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.dir("jniLibs")) { into("jniLibs") }
    from(layout.buildDirectory.dir("generated/audioAssets")) { into("assets") }
    from(layout.buildDirectory.dir("ffmpeg-distribution")) { into("sources") }
    from(layout.projectDirectory.file("zig/FFMPEG-NOTICE.txt"))
    from(layout.projectDirectory.file("zig/vendor/MINIAUDIO-LICENSE")) { into("licenses/miniaudio") }
}
