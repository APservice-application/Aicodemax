# Native Toolchain (CP-118)

Prebuilt arm64 tools embedded in the APK (ported from the owner's previous app):

| Tool | File | Source |
|---|---|---|
| ffmpeg | `libffmpeg.so` (20MB) | release `archive/oldai-workspace` asset `libffmpeg-arm64.so` |
| ffprobe | `libffprobe.so` (20MB) | asset `libffprobe-arm64.so` |
| llama-server | `libllama-server.so` (13MB) | asset `libllama-server-arm64.so` — INTERIM, removed at CP-128 |
| llama.cpp | `libllama.so` | **built from source in CI** (CP-122, see below) |

## llama.cpp from source (CP-122)

Spec §41 P3–P4: the inference engine is compiled, never downloaded as a
binary blob.

- CI step "Build llama.cpp arm64": installs NDK `27.0.12077973` via
  sdkmanager, clones `ggml-org/llama.cpp` at pinned tag **v0.4.1**
  (`--depth 1`), configures with the NDK CMake toolchain
  (`arm64-v8a`, `android-26`, Release, `BUILD_SHARED_LIBS=ON`,
  `CURL/TESTS/EXAMPLES/SERVER/TOOLS=OFF`), builds, and copies `libllama.so`
  into `app/src/main/jniLibs/arm64-v8a/`.
- The built `.so` is cached (`actions/cache`, key
  `llama-v0.4.1-ndk-27.0.12077973`) — rebuilds only when the tag/NDK changes.
- Nothing from llama.cpp is committed to git (source too big, binary
  reproducible from the pinned tag).

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
