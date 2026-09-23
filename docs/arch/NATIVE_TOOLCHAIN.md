# Native Toolchain (CP-118)

Prebuilt arm64 tools embedded in the APK (ported from the owner's previous app):

| Tool | File | Source |
|---|---|---|
| ffmpeg | `libffmpeg.so` (20MB) | release `archive/oldai-workspace` asset `libffmpeg-arm64.so` |
| ffprobe | `libffprobe.so` (20MB) | asset `libffprobe-arm64.so` |
| AI runtime | `libaicode_jni.so` (~5MB) | llama.cpp v0.4.1 + JNI, **built from source in CI** (CP-122/123) |

(CP-128 removed `libllama-server.so`: spec §42.4/42.5 bans localhost/CLI
inference — AI now runs in-process via JNI.)

## llama.cpp from source (CP-122) + JNI bridge (CP-123)

Spec §41 P3–P5: the inference engine is compiled, never downloaded as a
binary blob, and runs in-process via JNI (no localhost, no subprocess).

- CI step "Build llama.cpp + JNI arm64": installs NDK `27.0.12077973`,
  clones `ggml-org/llama.cpp` at pinned tag **v0.4.1** (`--depth 1`),
  configures with the NDK CMake toolchain (`arm64-v8a`, `android-26`,
  Release, PIC, `GGML_OPENMP=OFF`, `CURL/TESTS/EXAMPLES/SERVER/TOOLS=OFF`),
  builds static `llama` + `llama-common`, then links them with our wrapper
  `app/src/main/cpp/aicode_jni.cpp` into ONE stripped shared lib
  `libaicode_jni.so` in `app/src/main/jniLibs/arm64-v8a/`.
- The built `.so` is cached (`actions/cache`, key includes the tag, the NDK
  version and a hash of `app/src/main/cpp/**`) — rebuilds only when one of
  those changes.
- Nothing from llama.cpp is committed to git (source too big, binary
  reproducible from the pinned tag).
- JNI surface (`AicodeJni` ↔ `aicode_jni.cpp`): load / generate (streaming
  callback, stop sequences, cancellation) / stop / unload / info / version /
  lastError. `JniAiRuntime` implements `AiRuntime` over it with Qwen2.5
  ChatML framing; on plain JVM every call fails honestly ("Android only").

## Build flow (repo stays lean — no binaries in git)

1. CI step "Fetch embedded native tools" downloads the 3 `.so` files via
   `gh release download archive/oldai-workspace` into
   `app/src/main/jniLibs/arm64-v8a/` (renamed to `lib<tool>.so`).
2. `app/build.gradle.kts` sets `jniLibs.useLegacyPackaging = true` so Android
   extracts them to `nativeLibraryDir` with the exec bit set.
3. `androidResources.noCompress += "gguf"` reserves the bootstrap-model path (CP-120).

Local builds without the fetch step still compile — tools simply report
"missing" at runtime (honest, never fake).

## Runtime

- `tools/runtime/NativeToolchain` (pure JVM): `resolve()` + `detect()` + `format()`.
- `ServiceLocator` wires `nativeLibraryDir` + `ProcessRunner` into
  `DebugToolExecutor(nativeLibDir, nativeRunner)`.
- Chat/gateway: `debug` → `native` action (capability `debug.native`).

## Real media ops (CP-119)

- `tools/runtime/Ffmpeg` (pure JVM): `probeFile()` (ffprobe flat output, no JSON
  dep) + `exportFile()` (transcode/trim/scale; safe default codecs
  mpeg4+aac, overridable via `vcodec`/`acodec`) + injected `Runner`.
- Gateway: `media` → `asset.probe` (path → duration/resolution/codecs) and
  `media` → `timeline.export` (path + output?/startMs?/durationMs?/w?/h? →
  real output file). Missing binary/input → honest error, never fake output.
- `ServiceLocator` wires `nativeLibraryDir` + `ProcessRunner` adapter into
  `MediaToolExecutor`.

## On-device model (CP-120)

- `tools/runtime/ModelStore` (pure JVM): `DEFAULT_MODEL` = Qwen2.5-0.5B
  Q4_K_M from HuggingFace (~400MB, same as the owner's previous app).
  `status()` / `download()` (resume via Range, 95% size gate, `.part` +
  rename) into `filesDir/models`. Never in git, never in the APK.
- `tools/runtime/LlamaServer` (pure JVM): `argv()` + `serve()`/`stop()` via
  injected `ProcCtl`, `/health` check, `/v1/chat/completions` ask with a
  tiny dependency-free JSON extractor. No org.json, no OkHttp.
- Gateway: `model` → `status`/`download`/`serve`/`stop`/`ask`
  (`ModelToolExecutor`, capabilities `model.*`). `ServiceLocator` wires
  `filesDir/models` + `urlDownloader()` + ProcessBuilder process control.
