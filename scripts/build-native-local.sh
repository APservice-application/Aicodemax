#!/bin/bash
# CP-145: build the CI-packed native libs on your own machine for LOCAL APK builds.
# Produces: libaicode_jni.so (AI) + libaicode_whisper.so (STT) + libaicode_pty.so (terminal)
# into app/src/main/jniLibs/arm64-v8a/ (git-ignored; CI rebuilds them anyway).
#
# Prereqs: Android SDK with NDK 27.0.12077973 + CMake (install via Android
# Studio > SDK Manager), plus: git cmake curl python3, ~15 min first run.
# NOTE: libffmpeg/libffprobe are skipped (media tools only; CI fetches them
# from a release) — video/audio tools will report honestly without them.
set -e
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for guess in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -d "$guess" ] && SDK="$guess" && break
  done
fi
[ -z "$SDK" ] && { echo "ERROR: set ANDROID_HOME to your Android SDK (or install via Android Studio)."; exit 1; }
NDK="${NDK_DIR:-$SDK/ndk/27.0.12077973}"
[ -d "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
  echo "ERROR: NDK 27.0.12077973 not found in $SDK/ndk."
  echo "Install it: Android Studio > Settings > Appearance > System Settings > Android SDK > SDK Tools > NDK (27.0.12077973) + CMake > Apply."
  exit 1
}
command -v cmake >/dev/null || { echo "ERROR: cmake not found (install via SDK Manager > SDK Tools > CMake, and add to PATH)."; exit 1; }
command -v git >/dev/null || { echo "ERROR: git not found."; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl not found."; exit 1; }

OS="$(uname -s)"
case "$OS" in
  Darwin) PREBUILT="darwin-x86_64"; JOBS="$(sysctl -n hw.ncpu)" ;;
  *) PREBUILT="linux-x86_64"; JOBS="$(nproc 2>/dev/null || echo 4)" ;;
esac
TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/$PREBUILT/bin"
CC="$TOOLCHAIN_BIN/aarch64-linux-android26-clang"
CXX="$TOOLCHAIN_BIN/aarch64-linux-android26-clang++"
[ -x "$CXX" ] || { echo "ERROR: NDK toolchain missing: $CXX"; exit 1; }

mkdir -p app/src/main/jniLibs/arm64-v8a

echo "=== llama.cpp + aicode_jni (AI) ==="
if [ ! -d /tmp/aicode-llamacpp ]; then
  git clone --depth 1 --branch v0.4.1 https://github.com/ggml-org/llama.cpp.git /tmp/aicode-llamacpp
fi
cmake -S /tmp/aicode-llamacpp -B /tmp/aicode-llamacpp/build-android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF
cmake --build /tmp/aicode-llamacpp/build-android --config Release -j"$JOBS" --target llama
cmake --build /tmp/aicode-llamacpp/build-android --config Release -j"$JOBS" --target llama-common || \
cmake --build /tmp/aicode-llamacpp/build-android --config Release -j"$JOBS" --target common || \
echo "no common target (libllama only)"
STATIC_LIBS=$(find /tmp/aicode-llamacpp/build-android \( -name "libllama*.a" -o -name "libggml*.a" \) | tr '\n' ' ')
test -n "$STATIC_LIBS"
"$CXX" \
  -shared -fPIC -O2 -std=c++17 \
  -I/tmp/aicode-llamacpp/include -I/tmp/aicode-llamacpp/ggml/include -I/tmp/aicode-llamacpp/common \
  app/src/main/cpp/aicode_jni.cpp \
  -Wl,--start-group $STATIC_LIBS -Wl,--end-group \
  -llog -landroid \
  -o app/src/main/jniLibs/arm64-v8a/libaicode_jni.so
"$TOOLCHAIN_BIN/llvm-strip" app/src/main/jniLibs/arm64-v8a/libaicode_jni.so

echo "=== whisper.cpp + aicode_whisper (STT) ==="
if [ ! -d /tmp/aicode-whispercpp ]; then
  git clone --depth 1 --branch v1.9.4 https://github.com/ggml-org/whisper.cpp.git /tmp/aicode-whispercpp
fi
cmake -S /tmp/aicode-whispercpp -B /tmp/aicode-whispercpp/build-android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DGGML_OPENMP=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
cmake --build /tmp/aicode-whispercpp/build-android --config Release -j"$JOBS" --target whisper
WSTATIC_LIBS=$(find /tmp/aicode-whispercpp/build-android \( -name "libwhisper*.a" -o -name "libggml*.a" \) | tr '\n' ' ')
test -n "$WSTATIC_LIBS"
"$CXX" \
  -shared -fPIC -O2 -std=c++17 \
  -I/tmp/aicode-whispercpp/include -I/tmp/aicode-whispercpp/ggml/include \
  app/src/main/cpp/whisper_jni.cpp \
  -Wl,--start-group $WSTATIC_LIBS -Wl,--end-group \
  -llog -landroid \
  -o app/src/main/jniLibs/arm64-v8a/libaicode_whisper.so
"$TOOLCHAIN_BIN/llvm-strip" app/src/main/jniLibs/arm64-v8a/libaicode_whisper.so

echo "=== aicode_pty (interactive terminal) ==="
"$CC" \
  -shared -fPIC -O2 \
  app/src/main/cpp/aicode_pty.c \
  -llog \
  -o app/src/main/jniLibs/arm64-v8a/libaicode_pty.so
"$TOOLCHAIN_BIN/llvm-nm" --defined-only app/src/main/jniLibs/arm64-v8a/libaicode_pty.so | grep -q "ptyOpen"
"$TOOLCHAIN_BIN/llvm-strip" app/src/main/jniLibs/arm64-v8a/libaicode_pty.so

echo "=== done ==="
ls -la app/src/main/jniLibs/arm64-v8a/
