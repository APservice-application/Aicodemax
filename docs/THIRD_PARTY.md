# THIRD-PARTY SOFTWARE

## Termux (terminal emulator application for Android)
- Source: https://github.com/termux/termux-app
- License: GNU General Public License v3.0 only (GPLv3)
- Used as: embedded terminal stack via pinned git submodule + own integration
  module (see `docs/TERMINAL_PLAN.md`, AMENDMENT-001/002)
- Copyright: Termux contributors (see upstream repository for full notices)

## Eclipse JGit (pure-Java git implementation)
- Source: https://www.eclipse.org/jgit/
- License: Eclipse Distribution License v1.0 (BSD-3-Clause style)
- Used as: Maven dependency `org.eclipse.jgit` (version pinned in `gradle/libs.versions.toml`),
  wrapped by our `GitPort` interface (`tools/git`) — see `JGitGitPort.kt`
- Copyright: Eclipse Foundation and JGit contributors

## Android-Terminal-Emulator (terminal lineage via Termux)
- Source: https://github.com/jackpal/Android-Terminal-Emulator
- License: Apache License 2.0
- Copyright: Jack Palevich and contributors

## Qwen3-1.7B Q4_K_M built-in AI model
- Base model: [Qwen/Qwen3-1.7B](https://huggingface.co/Qwen/Qwen3-1.7B) (Qwen team).
- Q4_K_M GGUF quantization: [unsloth/Qwen3-1.7B-GGUF](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF), pinned revision `cc27747d7419139e44ba97777c2f2fd5dca92ee1`, file `Qwen3-1.7B-Q4_K_M.gguf` (SHA-256 recorded in `docs/qa/android-bundled-qwen3-1p7b-2026-09-26.md`). Qwen's own GGUF repository does not publish this Q4 file in the checked revision.
- License: Apache License 2.0 per source/base model cards. Full text from the Qwen3-1.7B base model's pinned `LICENSE` is included in the APK at `assets/licenses/Qwen3-1.7B-APACHE-2.0.txt`.
- Use: redistributed as model weights inside the Android debug APK; runtime uses llama.cpp JNI, no user download needed.

## GNU GPL v3 license text
- Full text in `LICENSE` at repo root, or https://www.gnu.org/licenses/gpl-3.0.txt
