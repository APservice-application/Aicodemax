import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aicodemax.app"
    compileSdk = 34

    // CP-146: pinned NDK — the Gradle build itself compiles the AI engine
    // (llama.cpp/whisper.cpp/pty JNI) on ANY machine. Auto-installed on first
    // build by the ensureNativeBuildTools task below when sdkmanager exists.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.aicodemax"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.5"

        // CP-146: arm64 only (same ABI the old CI shell build produced).
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // -O2 even for debug builds: llama.cpp at -O0 is unusably slow.
                cFlags += "-O2"
                cppFlags += "-O2"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // CP-146: the AI engine is part of the Gradle build. assembleDebug on ANY
    // machine compiles the JNI libs and bundles them into the APK — no manual
    // native builds, no CI-only shell scripts.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // CP-118: embedded native tools (.so) must extract to nativeLibraryDir with
    // the exec bit set (same approach as the owner's previous app).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        // CP-120: bootstrap .gguf model must not be compressed.
        noCompress += "gguf"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:state"))
    implementation(project(":core:resources"))
    implementation(project(":ai:core"))
    implementation(project(":ai:runtime"))
    implementation(project(":ai:tasks"))
    implementation(project(":ai:agents"))
    implementation(project(":ai:models"))
    implementation(project(":tools:registry"))
    implementation(project(":tools:capability"))
    implementation(project(":tools:gateway"))
    implementation(project(":tools:files"))
    implementation(project(":tools:editor"))
    implementation(project(":tools:terminal"))
    implementation(project(":tools:terminal_runtime"))
    implementation(project(":tools:runtime"))
    implementation(project(":tools:project"))
    implementation(project(":tools:tester"))
    implementation(project(":tools:builder"))
    implementation(project(":tools:git"))
    implementation(project(":tools:git_runtime"))
    implementation(project(":tools:debug"))
    implementation(project(":tools:debug_runtime"))
    implementation(project(":tools:memory_runtime"))
    implementation(project(":data:skills"))
    implementation(project(":tools:skill_runtime"))
    implementation(project(":tools:github"))
    implementation(project(":tools:supabase"))
    implementation(project(":tools:supabase_runtime"))
    implementation(project(":tools:webai"))
    implementation(project(":tools:browser"))
    implementation(project(":tools:browser_runtime"))
    implementation(project(":tools:voice"))
    implementation(project(":tools:voice_runtime"))
    implementation(project(":tools:image"))
    implementation(project(":tools:image_runtime"))
    implementation(project(":tools:audio"))
    implementation(project(":tools:audio_runtime"))
    implementation(project(":tools:video"))
    implementation(project(":tools:video_runtime"))
    implementation(project(":tools:subtitle"))
    implementation(project(":tools:subtitle_runtime"))
    implementation(project(":tools:render"))
    implementation(project(":tools:render_runtime"))
    implementation(project(":data:media"))
    implementation(project(":tools:media"))
    implementation(project(":tools:media_runtime"))
    implementation(project(":data:checkpoint"))
    implementation(project(":data:audit"))
    implementation(project(":data:memory"))
    implementation(project(":data:conversations"))
    implementation(project(":data:settings"))
    implementation(project(":ui:designsystem"))
    implementation(project(":ui:chat"))
    implementation(project(":ui:workspace"))
    implementation(project(":ui:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation("androidx.core:core:1.13.1") // FileProvider for in-app camera capture
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
}

// CP-146: BUILD-TIME AI EMBEDDING. Every assemble* on ANY machine produces an
// APK with the engine + model inside. Nothing for the developer to run by hand,
// nothing for the user to download/install. Internet is needed at BUILD time
// only (one-time fetch, then cached next to the sources); the installed app
// runs fully OFFLINE.
val builtinModelUrl =
    "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
val builtinModelSize = 2497280256L // exact bytes (Qwen3-4B Q4_K_M, Apache-2.0)
// CP-147: AGP compressDebugAssets buffers each asset in memory (2GB array
// cap) — the 2.5GB model ships as 512MiB parts + manifest, joined on device.
val builtinModelPartSize = 536870912L
val builtinModelAssetDir = file("src/main/assets/ai")
val builtinModelManifest = file("src/main/assets/ai/builtin-model.manifest")

fun builtinModelParts(): List<File> {
    val n = ((builtinModelSize + builtinModelPartSize - 1) / builtinModelPartSize).toInt()
    return (0 until n).map { i -> File(builtinModelAssetDir, "builtin-model-part%02d.gguf".format(i)) }
}

fun builtinModelPartsValid(): Boolean {
    if (!builtinModelManifest.isFile) return false
    val kv = builtinModelManifest.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) null
        else t.substringBefore("=").trim() to t.substringAfter("=").trim()
    }.toMap()
    val parts = kv["parts"]?.toIntOrNull() ?: return false
    val total = kv["total"]?.toLongOrNull() ?: return false
    if (total != builtinModelSize) return false
    val expected = builtinModelParts()
    if (expected.size != parts) return false
    for (i in expected.indices) {
        val want = if (i < parts - 1) builtinModelPartSize else total - builtinModelPartSize * (parts - 1)
        if (!expected[i].isFile || expected[i].length() != want) return false
    }
    return verifyGgufHeader(expected[0])
}
val ffmpegArm64Dir = file("src/main/jniLibs/arm64-v8a")

fun resolveAndroidSdkDir(): File {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) localProps.inputStream().use { props.load(it) }
    val dir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException(
            "CP-146: Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.",
        )
    return File(dir)
}

tasks.register("ensureNativeBuildTools") {
    description = "CP-146: installs pinned NDK + CMake via sdkmanager when missing."
    doLast {
        val sdkDir = resolveAndroidSdkDir()
        val ndkDir = File(sdkDir, "ndk/27.0.12077973")
        val cmakeDir = File(sdkDir, "cmake/3.22.1")
        if (ndkDir.isDirectory && cmakeDir.isDirectory) {
            logger.lifecycle("ensureNativeBuildTools: NDK 27.0.12077973 + CMake 3.22.1 present.")
            return@doLast
        }
        val win = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val sdkman = File(sdkDir, "cmdline-tools/latest/bin/sdkmanager" + if (win) ".bat" else "")
        if (!sdkman.exists()) {
            throw GradleException(
                "CP-146: NDK 27.0.12077973 and/or CMake 3.22.1 missing and no cmdline-tools " +
                    "sdkmanager found. Install them once via Android Studio SDK Manager or run: " +
                    "sdkmanager \"ndk;27.0.12077973\" \"cmake;3.22.1\"",
            )
        }
        logger.lifecycle("ensureNativeBuildTools: installing NDK 27.0.12077973 + CMake 3.22.1 (one-time)...")
        val yes = ByteArrayInputStream("y\n".repeat(40).toByteArray())
        exec {
            commandLine(sdkman.absolutePath, "--licenses")
            standardInput = yes
            isIgnoreExitValue = true
        }
        exec { commandLine(sdkman.absolutePath, "ndk;27.0.12077973", "cmake;3.22.1") }
        require(ndkDir.isDirectory && cmakeDir.isDirectory) { "CP-146: native tools install failed." }
    }
}

fun verifyGgufHeader(f: File): Boolean =
    f.inputStream().use { inp ->
        val magic = ByteArray(4)
        inp.read(magic) == 4 && magic.contentEquals("GGUF".toByteArray())
    }

tasks.register("fetchBuiltinModel") {
    description = "CP-147: fetches the pinned built-in GGUF as 512MiB asset parts + manifest (skipped when present + verified)."
    doLast {
        // Legacy single-file asset from the older pipeline must not linger.
        val legacy = File(builtinModelAssetDir, "builtin-model.gguf")
        if (legacy.exists()) legacy.delete()
        if (builtinModelPartsValid()) {
            logger.lifecycle("fetchBuiltinModel: parts present + verified, skipping.")
            return@doLast
        }
        builtinModelAssetDir.mkdirs()
        val parts = builtinModelParts()
        val tmps = parts.map { File(it.path + ".part") }
        tmps.forEach { it.delete() }
        parts.forEach { it.delete() }
        builtinModelManifest.delete()
        logger.lifecycle("fetchBuiltinModel: downloading built-in model 2.5GB as ${parts.size} parts (one-time, build-time only)...")
        var total = 0L
        try {
            URL(builtinModelUrl).openStream().use { inp ->
                val buf = ByteArray(8 * 1024 * 1024)
                var partIdx = 0
                var partWritten = 0L
                var out = Files.newOutputStream(tmps[0].toPath())
                try {
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        var off = 0
                        var left = n
                        while (left > 0) {
                            val room = builtinModelPartSize - partWritten
                            val take = minOf(room, left.toLong()).toInt()
                            out.write(buf, off, take)
                            off += take
                            left -= take
                            partWritten += take
                            total += take
                            if (partWritten == builtinModelPartSize && total < builtinModelSize) {
                                out.close()
                                partIdx += 1
                                if (partIdx >= tmps.size) throw GradleException("CP-147: model larger than pinned size.")
                                out = Files.newOutputStream(tmps[partIdx].toPath())
                                partWritten = 0
                            }
                        }
                    }
                } finally {
                    try {
                        out.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            tmps.forEach { it.delete() }
            throw e
        }
        if (total != builtinModelSize) {
            tmps.forEach { it.delete() }
            throw GradleException("CP-147: model size mismatch (got $total, want $builtinModelSize).")
        }
        if (!verifyGgufHeader(tmps[0])) {
            tmps.forEach { it.delete() }
            throw GradleException("CP-147: downloaded file is not GGUF.")
        }
        tmps.forEachIndexed { i, tmp ->
            if (!tmp.renameTo(parts[i])) throw GradleException("CP-147: cannot move part $i into assets.")
        }
        builtinModelManifest.writeText("# written by fetchBuiltinModel - do not edit\nparts=${parts.size}\ntotal=$builtinModelSize\n")
        logger.lifecycle("fetchBuiltinModel: done (${parts.size} parts).")
    }
}

tasks.register("fetchFfmpegLibs") {
    description = "CP-146: fetches prebuilt ffmpeg/ffprobe arm64 libs into jniLibs (skipped when present)."
    doLast {
        val base = "https://github.com/APservice-application/Aicodemax/releases/download/archive/oldai-workspace"
        val pairs = listOf("libffmpeg-arm64.so" to "libffmpeg.so", "libffprobe-arm64.so" to "libffprobe.so")
        ffmpegArm64Dir.mkdirs()
        for ((remote, local) in pairs) {
            val dest = File(ffmpegArm64Dir, local)
            if (dest.exists() && dest.length() > 1_000_000) {
                logger.lifecycle("fetchFfmpegLibs: $local present, skipping.")
                continue
            }
            logger.lifecycle("fetchFfmpegLibs: downloading $local (one-time)...")
            val tmp = File(dest.path + ".part")
            URL("$base/$remote").openStream().use { inp ->
                Files.copy(inp, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (tmp.length() < 1_000_000 || !tmp.renameTo(dest)) {
                tmp.delete()
                throw GradleException("CP-146: failed to fetch $local.")
            }
        }
    }
}

tasks.register("verifyEmbeddedAi") {
    description = "CP-146: BUILD=FAIL unless every built APK contains the AI engine + model."
    doLast {
        val apks = file("build/outputs/apk").walkTopDown().filter { it.extension == "apk" }.toList()
        if (apks.isEmpty()) {
            logger.lifecycle("verifyEmbeddedAi: no APK built, nothing to verify.")
            return@doLast
        }
        // CP-147: model ships as parts + manifest (AGP cannot buffer one 2.5GB asset).
        val required = listOf(
            "lib/arm64-v8a/libaicode_jni.so",
            "lib/arm64-v8a/libaicode_whisper.so",
            "lib/arm64-v8a/libaicode_pty.so",
            "lib/arm64-v8a/libffmpeg.so",
            "lib/arm64-v8a/libffprobe.so",
            "lib/arm64-v8a/libc++_shared.so",
        ) + builtinModelParts().map { "assets/ai/" + it.name } + "assets/ai/builtin-model.manifest"
        // The engines must be statically linked INTO libaicode_*.so — never loose
        // shared libs (a 44KB wrapper + libwhisper.so once slipped through).
        val forbidden = listOf("libwhisper.so", "libllama.so", "libggml.so", "libggml-base.so")
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val names = Collections.list(zip.entries()).map { it.name }.toSet()
                val missing = required.filter { it !in names }
                if (missing.isNotEmpty()) {
                    throw GradleException("CP-146 BUILD=FAIL: ${apk.name} lacks embedded AI: $missing")
                }
                val loose = names.filter { n -> forbidden.any { f -> n.endsWith("/$f") } }
                if (loose.isNotEmpty()) {
                    throw GradleException("CP-146 BUILD=FAIL: ${apk.name} has loose engine libs (must be static): $loose")
                }
                logger.lifecycle("verifyEmbeddedAi: ${apk.name} contains engine + model. OK.")
            }
        }
    }
}

tasks.register("verifyNativeSymbols") {
    description = "CP-146: BUILD=FAIL unless the JNI libs are self-contained (ex-old-CI nm/size guards)."
    doLast {
        val ndkDir = File(resolveAndroidSdkDir(), "ndk/27.0.12077973")
        // Host prebuilt dir differs per OS (linux/darwin/windows) — glob it.
        val nm = File(ndkDir, "toolchains/llvm/prebuilt").walkTopDown()
            .filter { it.isFile && it.name.startsWith("llvm-nm") && !it.extension.equals("dll", true) }
            .firstOrNull()
            ?: throw GradleException("CP-146: llvm-nm not found under ${ndkDir.path}.")
        // Unstripped outputs (symbols intact) for every variant/ABI built.
        // Roots: intermediates copy + .cxx ninja outputs (module-level or under build/).
        val roots = listOf("build/intermediates/cxx", ".cxx", "build/.cxx", "build/intermediates/cmake").map { file(it) }
        val allSo = projectDir.walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .map { it.relativeTo(projectDir).path }
            .toList()
        logger.lifecycle("verifyNativeSymbols: projectDir=$projectDir all .so (${allSo.size}): ${allSo.take(50)}.")
        val libs = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.name.startsWith("libaicode_") && it.extension == "so" }.toList()
        }.distinctBy { it.name }
        logger.lifecycle("verifyNativeSymbols: scanned ${roots.map { it.path }} (exist=${roots.map { it.isDirectory }}), found ${libs.map { it.name }}.")
        if (libs.isEmpty()) {
            logger.lifecycle("verifyNativeSymbols: no JNI libs built yet, nothing to verify.")
            return@doLast
        }
        fun nmOut(args: List<String>, lib: File): String {
            val out = ByteArrayOutputStream()
            exec {
                commandLine(listOf(nm.absolutePath) + args + lib.absolutePath)
                standardOutput = out
            }
            return out.toString(Charsets.UTF_8.name())
        }
        for (lib in libs) {
            when (lib.name) {
                "libaicode_jni.so" -> {
                    if (lib.length() < 2_000_000) throw GradleException("CP-146 BUILD=FAIL: ${lib.path} too small (${lib.length()}) — llama not linked?")
                    val undef = nmOut(listOf("--undefined-only"), lib)
                    if (undef.contains("llama_")) throw GradleException("CP-146 BUILD=FAIL: undefined llama_* symbols in ${lib.path}.")
                }
                "libaicode_whisper.so" -> {
                    if (lib.length() < 1_000_000) throw GradleException("CP-146 BUILD=FAIL: ${lib.path} too small (${lib.length()}) — whisper not linked?")
                    val undef = nmOut(listOf("--undefined-only"), lib)
                    if (undef.contains("whisper_")) throw GradleException("CP-146 BUILD=FAIL: undefined whisper_* symbols in ${lib.path}.")
                }
                "libaicode_pty.so" -> {
                    val defined = nmOut(listOf("--defined-only"), lib)
                    if (!defined.contains("ptyOpen") || !defined.contains("ptyClose")) {
                        throw GradleException("CP-146 BUILD=FAIL: pty symbols missing in ${lib.path}.")
                    }
                }
            }
            logger.lifecycle("verifyNativeSymbols: ${lib.name} (${lib.length()} bytes) OK.")
        }
    }
}

// Wire embedding into the standard build graph (no manual steps, any machine).
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn("ensureNativeBuildTools") }
tasks.matching { it.name.startsWith("buildCMake") }
    .configureEach { finalizedBy("verifyNativeSymbols") }
tasks.matching { it.name.matches(Regex("merge.*Assets")) }
    .configureEach { dependsOn("fetchBuiltinModel") }
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }
    .configureEach { dependsOn("fetchFfmpegLibs") }
tasks.matching { it.name.matches(Regex("assemble.*")) }
    .configureEach { finalizedBy("verifyEmbeddedAi") }
