# Phase 1 Audit — Embedded Native AI spec vs current app (CP-121 pre)

Spec: `docs/requirements/embedded-native-ai.md` (§0–43).
App head at audit time: `2633d50` (v0.1.4, CP-113..120 shipped).
Date: 2026-09-23.

Legend: ✅ have / 🟡 partial / ❌ missing / ⛔ violates spec.

## Layer map (§1–§4)

| Spec | Status | Current reality |
|---|---|---|
| UI → AIService → AgentRuntime → ModelProvider → NativeRuntime → JNI → libllama.so | 🟡 | UI → Orchestrator → Planner → ToolGateway → executors ✅; AIService / ModelProvider / NativeRuntime / JNI ❌ |
| JNI API (load/generate/stop/unload/info/params/streaming) | ❌ | No NDK, no .cpp, no JNI in repo |
| Inference in-app process, no localhost/CLI | ⛔ | CP-120 serves via **llama-server binary + localhost:8080** → violates §42.4/42.5; interim only, must be replaced by JNI (see plan) |

## Models (§5–§9, §23–§25)

| Spec | Status | Current reality |
|---|---|---|
| ModelManager (load/unload/switch/versions/packs/RAM+arch checks) | 🟡 | ModelStore: status/download only (CP-120) |
| Default model usable after install (§6–§8) | 🟡 | On-demand 400MB download; no first-run delivery UX; APK 42MB has no model |
| Provider abstraction Local/Cloud/Remote/Hybrid (§9) | ❌ | Only media CloudGenProvider (image-gen); ChatBrain has no provider switch |
| Storage layout /models/default|optional, /runtime, /agent… (§23) | 🟡 | filesDir: audit/state/skills/media/workspace/models — flat, must migrate |
| Integrity (checksum/format/arch) (§24) | 🟡 | 95% size gate only |
| License registry (§25) | ❌ | Qwen2.5-0.5B-Instruct-GGUF verified **Apache-2.0** (redistributable OK) — must record in code |

## Agent + tools (§10–§17)

| Spec | Status | Current reality |
|---|---|---|
| Orchestrator multi-step + planner (§10) | ✅ | BootstrapOrchestrator + RuleBasedPlanner + TaskStore + checkpoints |
| Terminal is a tool (§11) | ✅ | CP-113 |
| Tool Registry + metadata (§12) | 🟡 | ToolRegistry + CapabilityBinding (purpose/inputs/outputs/permissions); missing: preconditions, result schema, risk level per tool |
| Tool Retrieval top 3–5 (§13) | 🟡 | RuleBasedPlanner keyword intents only; no candidate pruning, no history-based retrieval |
| Grammar-constrained calling (§14) | ❌ | Blocked until JNI/GBNF exists; JSON schema export can be prepared |
| Validation pipeline + retry 2–3 (§15) | ❌ | Ad-hoc arg checks per executor; no schema/permission/precondition chain |
| Dynamic few-shot (§16) | ❌ | Have per-tool lessons (CP-114) but no example store/retrieval |
| LoRA (§17) | ⏳ | Future by design (needs dataset from P25+) |

## Runtime + resources (§18–§22)

| Spec | Status | Current reality |
|---|---|---|
| ResourceManager (RAM/CPU/storage/adaptive ctx) (§18) | ❌ | (tools/runtime/RuntimeManager is language runtimes — different thing) |
| Memory pressure (§19) | ❌ | |
| AiRuntimeManager lifecycle (§20) | ❌ | No AI runtime states |
| Bg init, no blocking load (§21) | ❌ | No model load at all yet — design bg-first from the start |
| Crash/recovery (§22) | ❌ | |

## Cross-cutting (§26–§40)

| Spec | Status | Current reality |
|---|---|---|
| Offline-first (§26) | 🟡 | All local except model download; network-needing tools not flagged per-tool |
| Hybrid local+cloud (§27) | ❌ | |
| Permissions LOW/MED/HIGH + confirm (§28) | 🟡 | SAFE/RISKY + ApprovalCenter/PermissionGate ✅ but 2 levels only |
| Browser first-class (§29) | 🟡 | Tabs + read/click/type (CP-115); cookies/cache/storage per-tab partial |
| Context split global/conv/project/tab/tool/model (§30) | 🟡 | ContextEngine = token fit only |
| Memory levels (§31) | 🟡 | Conversation ✅, tool lessons ✅, project/long-term partial |
| Observability chain (§32) | 🟡 | FileAuditLog ✅; missing retrieved-tools→decision→validation trace |
| UI states (§33) | 🟡 | sending/skeleton; no Ready/Thinking/Running-Tool/Retry states |
| Models/Runtime/Tools UIs (§34–36) | ❌ | |
| Dev tools as modules (§38), media via tools (§39) | ✅ | |

## Decisions (from audit)

1. **CP-120 localhost transport is interim, not architecture.** The spec bans
   localhost/CLI as the main inference path. `LlamaServer.serve/ask` stays only
   until the JNI runtime proves itself on-device, then the serve path and the
   13MB `libllama-server.so` fetch are removed (scheduled at CP-128).
   `ModelStore` (download/integrity) is kept — delivery is still needed.
2. **New Kotlin module `ai/runtime`** will own AiRuntime + providers +
   AiRuntimeManager + ResourceManager (pure JVM where possible, Android only
   at the JNI edge) so kc-build + unit tests keep covering it.
3. **NDK build (P3–P4)** compiles llama.cpp from source in CI (pinned tag,
   arm64-v8a, no curl/server/examples/tests) + our JNI wrapper; app loads
   `libllama.so` + `libaicode_jni.so` from nativeLibraryDir like the ffmpeg .so files.
4. **License gate (§25):** every ModelSpec gains license+source fields; only
   Apache-2.0/MIT/equivalent models may ship as default.

## Checkpoint plan (§41 order)

| CP | Phases | Content |
|---|---|---|
| CP-121 | P2 | AiRuntime abstraction + FakeAiRuntime + interim LlamaServerRuntime adapter + tests |
| CP-122 | P3+P4 | NDK + llama.cpp arm64 build in CI (pinned tag, shared lib) |
| CP-123 | P5 | JNI bridge + JniAiRuntime wrapper + streaming/stop |
| CP-124 | P6+P9 | ModelProvider Local/Cloud/Remote/Hybrid + LocalModelProvider |
| CP-125 | P7+P23–25 | ModelManager (profiles/versions/validate/license/packs) + storage migration |
| CP-126 | P8+P20–22 | AiRuntimeManager lifecycle + bg init + crash/recovery |
| CP-127 | P9+P18–19 | ResourceManager + adaptive ctx/threads + memory pressure |
| CP-128 | P10–11 | Default-model delivery + real local chat (streaming/stop); localhost path removed |
| CP-129 | P12–13 | Registry metadata completion + Tool Retrieval top 3–5 |
| CP-130 | P14–16 | Validator pipeline + retry + dynamic few-shot + GBNF schema export |
| CP-131 | P17–20 | Orchestrator wiring + §33 states + Models/Runtime/Tools screens |
| CP-132 | P21–24 | Offline/low-RAM/crash/device test passes + APK |
| later | P25–29 | Dataset → LoRA → quantize → deploy via ModelManager |
