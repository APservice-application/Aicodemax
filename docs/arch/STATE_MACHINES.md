# STATE MACHINES — Phase 0

## 1. Task lifecycle (จาก โครงสร้าง §5 — implement จริงใน `:ai:tasks`)
```
CREATED → QUEUED → PLANNING → READY → RUNNING → VERIFYING → COMPLETED
              │        │         │        │
              │        │         │        ├─→ WAITING ─→ RUNNING
              │        │         │        ├─→ WAITING_USER ─→ RUNNING | CANCELLED
              │        │         │        ├─→ WAITING_PERMISSION ─→ RUNNING | CANCELLED
              │        │         │        ├─→ RETRYING ─→ RUNNING
              │        │         │        ├─→ FAILED ─→ RETRYING | ROLLED_BACK | CANCELLED
              │        │         │        └─→ BLOCKED ─→ READY | CANCELLED
              └────────┴─────────┴──────────→ CANCELLED (ยกเลิกได้ทุกสถานะก่อน COMPLETED)
VERIFYING ─→ COMPLETED | FAILED
```

## 2. Terminal session (ใช้จริงใน Phase 16)
```
CREATED → STARTING → RUNNING ⇄ PAUSED → FINISHED
              │           │
              └→ FAILED ──┴→ (AI observe exit code → recover/retry ต่อ Task)
```

## 3. Tool call (ผ่าน Gateway)
```
PROPOSED → PERMISSION_CHECK → RESOURCE_CHECK → EXECUTING → OBSERVED
→ VERIFIED → DONE | DENIED | FAILED → (checkpoint + audit ทุกทาง)
```

## 4. Model status (Model Manager)
```
UNKNOWN → AVAILABLE | DOWNLOADING → READY ⇄ LOADED → UNLOADED
       → ERROR → (fallback ไปโมเดลถัดไปตาม router)
```
