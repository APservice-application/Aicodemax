#!/bin/bash
# CP-145: fetch the bundled built-in AI model for LOCAL builds.
# CI runs the same download automatically; run this once on your machine
# before `./gradlew assembleDebug`. (~470MB, Apache-2.0, redistributable.)
set -e
cd "$(dirname "$0")/.."
mkdir -p app/src/main/assets/ai
OUT=app/src/main/assets/ai/builtin-model.gguf

size_of() {
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1" 2>/dev/null || echo 0
}

if [ -f "$OUT" ]; then
  SIZE=$(size_of "$OUT")
  if [ "$SIZE" -gt 380000000 ] && [ "$(head -c 4 "$OUT")" = "GGUF" ]; then
    echo "model already present: $OUT ($SIZE bytes)"
    exit 0
  fi
  echo "existing file invalid (size=$SIZE), re-downloading..."
fi
curl -L --fail --retry 3 -o "$OUT" \
  https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
ls -la app/src/main/assets/ai/
test "$(size_of "$OUT")" -gt 380000000
test "$(head -c 4 "$OUT")" = "GGUF"
echo "OK: $OUT"
