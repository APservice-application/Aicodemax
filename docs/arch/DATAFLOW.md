# DATA FLOW — Phase 0

## F1: ผู้ใช้สั่งงานผ่าน Chat (flow หลักของทั้งแอป)
```
User types → ChatScreen → ChatViewModel → Orchestrator.plan()
  → Intent(model) → Planner → TaskGraph → TaskEngine.enqueue()
  → AgentRuntime.dispatch() → ModelRouter.pick() → ToolGateway.call()
  → Permission? → ResourceCheck → Runtime.execute()
  → Observation → Verification → Checkpoint → Audit
  → EventBus(AppEvent.TaskUpdated / ToolOutput) → UI render
  → Orchestrator ส่งสรุปกลับ Chat (message + action cards)
```

## F2: AI ทำงานกับ Terminal (AMENDMENT-001)
```
Task step (shell) → AIControlAPI.exec(session, cmd)
  → Pty/Session → stdout/stderr stream → Observation(buffered)
  → exit code → VERIFYING → 0: COMPLETED | else: RECOVERY (retry/fallback/ask user)
```

## F3: ผู้ใช้เปิด Tool เอง (two-way usage)
```
Workspace → CapabilityGate(descriptor) → ถ้า AVAILABLE เปิด runtime UI
→ การกระทำของผู้ใช้ปล่อย Event เดียวกับ AI ทำ (single source of truth)
```

## F4: Checkpoint / Rollback
```
ทุก VERIFIED step → CheckpointStore.save(taskId, state)
FAILED + rollbackable → CheckpointStore.load(lastGood) → TaskEngine → ROLLED_BACK
```

## F5: Settings / Theme / Legal
```
SettingsScreen → SettingsRepository(DataStore) → AppState.theme
About → LegalNotices(GPLv3 + Termux attribution) — ข้อบังคับ AMENDMENT-002
```
