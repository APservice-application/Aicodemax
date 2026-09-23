# Native Toolchain (CP-118)

Prebuilt arm64 tools embedded in the APK (ported from the owner's previous app):

| Tool | File | Source |
|---|---|---|
| ffmpeg | `libffmpeg.so` (20MB) | release `archive/oldai-workspace` asset `libffmpeg-arm64.so` |
| ffprobe | `libffprobe.so` (20MB) | asset `libffprobe-arm64.so` |
| llama-server | `libllama-server.so` (13MB) | asset `libllama-server-arm64.so` |

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
- Next: CP-120 downloads the Qwen GGUF bootstrap model and serves it via
  llama-server.
