# ARCHITECTURE CONTRACT — Phase 0

ที่มา: `docs/requirements/ระบบทั้งหมด.txt` (MASTER ARCHITECTURE),
`โครงสร้าง 1.txt` (BLUEPRINT), AMENDMENT-001/002.

## 1. นิยามระบบ (ห้ามตีความผิด)
```
APPLICATION = AI RUNTIME + TOOL RUNTIME + PROJECT RUNTIME + WORKSPACE + UI
```
- ไม่ใช่ Chatbot / API Client / WebView Wrapper
- Own AI First, Local-first; โมเดลภายนอก = Adapter
- แอปฟรี + GPLv3; Terminal ฝังใน (Termux submodule) — ดู `docs/TERMINAL_PLAN.md`

## 2. โครง 4 ชั้น (Four Layers)
```
LAYER 1 PRESENTATION   :ui:* + app shell (Compose, ห้ามมี logic ธุรกิจ)
        │  UiState / Events (one-way)
        ▼
LAYER 2 AI CORE        :ai:* (intent→plan→task→agent→model, ports & adapters)
        │  ToolCall / Observation
        ▼
LAYER 3 TOOL/RUNTIME   :tools:* + :core:resources (registry→gateway→runtime→verify)
        │  Checkpoint / Audit / Files
        ▼
LAYER 4 DATA/STATE     :data:* + :core:state (event bus, store, memory, settings)
```

## 3. Canonical runtime flow (ย่อจาก MASTER ARCHITECTURE §202)
```
HUMAN → UI → ORCHESTRATOR → TASK MANAGER → AGENT RUNTIME → MODEL MANAGER
→ TOOL GATEWAY → RESOURCE CHECK → RUNTIME/EXECUTION → OBSERVE → VERIFY
→ SUCCESS: CHECKPOINT → DONE | FAILURE: RECOVERY → RETRY/FALLBACK
```
ทุกขั้นปล่อย Event ลง `EventBus`; งานอันตรายต้องผ่าน Permission + Audit

## 4. กฎ Dependency (ตรวจทุก PR)
1. ห้าม dependency เป็นวง (acyclic) — ดู `MODULES.md`
2. ข้าม layer ได้แค่ชั้นติดกัน ยกเว้น `:core:common` ที่ทุกโมดูลใช้ได้
3. Core ห้ามรู้จัก Android UI; UI ห้ามเรียก Runtime ตรง (ผ่าน Gateway เท่านั้น)
4. พอร์ต (interface) อยู่ในโมดูลบน, อแดปเตอร์ (impl) อยู่โมดูลล่าง
   เช่น `ai:core` นิยาม `AgentExecutor`, `ai:agents` คือคน implement
5. โมดูล JVM-bpure (ไม่มี android.*) ต้องมี unit test และต้องผ่านใน CI `jvmTest`

## 5. แพ็กเกจและโมดูล
- Group: `com.aicodemax` — โครงโมดูลดู `MODULES.md`
- UI: Jetpack Compose + Material3 + Design tokens (`ui:designsystem`)
- DI วันแรก: `ServiceLocator` ใน `:app` (manual) — ย้าย Hilt ได้ภายหลังโดยไม่แก้ interface

## 6. 100% Capability Contract (ผูกกับโค้ด)
- ทุก Tool มี `ToolDescriptor + 7-layer Capability` (ดู `tools:registry`)
- UI แสดงเฉพาะ capability ที่ `AVAILABLE` จริง; สถานะอื่นแสดงเหตุผลตรงๆ ห้ามปุ่มหลอก
