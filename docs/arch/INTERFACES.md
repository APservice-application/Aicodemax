# INTERFACE CONTRACTS — Phase 0 (ไฟล์จริงในโค้ด)

## core:common (`com.aicodemax.core.common`)
- `Outcome<T>` — Success/Failure แทน exception ข้าม layer
- `AppLogger` — log กลาง (impl จริง: AndroidLog / JvmLog)

## core:state (`com.aicodemax.core.state`)
- `EventBus` — publish/subscribe `AppEvent` (impl จริง: SharedFlow)
- `AppEvent` — sealed: TaskUpdated, ToolOutput, ModelChanged, CapabilityChanged…

## core:resources (`com.aicodemax.core.resources`)
- `ResourceMonitor` — snapshot(): ram/storage/battery/network + `canRun(requirement)`

## tools:registry (`com.aicodemax.tools.registry`)
- `ToolRegistry` — register/descriptor/capabilities (impl จริง: in-memory)
- `CapabilityLayer` — UI/CONTROLLER/CAPABILITY_API/RUNTIME/EXECUTION/VERIFICATION/RECOVERY
- `CapabilityStatus` — AVAILABLE/PARTIAL/MISSING + เหตุผล (ผูก UI gate)

## tools:gateway (`com.aicodemax.tools.gateway`)
- `ToolGateway.call(ToolCall): ToolResult` — permission→resource→execute→audit
- `PermissionGate` (port) / `AuditHook` (port)

## ai:tasks (`com.aicodemax.ai.tasks`)
- `TaskEngine` — create/enqueue/transition/cancel (state machine จริง + ทดสอบแล้ว)
- `TaskState` — 15 สถานะตามสเปก + transition table

## ai:core (`com.aicodemax.ai.core`)
- `Orchestrator` (port) — handleUserMessage(): วางแผน+สั่งงาน+สรุปผล
- `IntentModel`, `Planner` (ports) — impl จริงเฟสหลัง, วันนี้มี HonestBootstrap (ตอบสถานะตรงๆ)

## ai:models (`com.aicodemax.ai.models`)
- `ModelRegistry/ModelRouter` — descriptor + routing + fallback chain
- `BootstrapAI` (port) — AI ฝังเครื่องขั้นต่ำ (impl จริงเมื่อมี model file)

## ai:agents (`com.aicodemax.ai.agents`)
- `AgentExecutor` (port อยู่ใน ai:core) — impl: `LocalAgentRunner`

## ai:memory → data:memory (`com.aicodemax.data.memory`)
- `MemoryStore` — save/recall (impl จริง: file JSON, JVM-testable)

## data:checkpoint / data:audit / data:conversations
- `CheckpointStore` / `AuditLog` / `ConversationStore` — file-based จริง + test แล้ว

## data:settings (`com.aicodemax.data.settings`)
- `SettingsRepository` — DataStore (theme/autonomy/model prefs)

## tools:files/editor/terminal/build/git/browser
- พอร์ตละ 1 interface หลัก + `descriptor()` คืน capability จริงของวันนี้
- terminal: `TerminalPort` + `AIControlAPI` (ต่อ Termux submodule ใน Phase 16)
