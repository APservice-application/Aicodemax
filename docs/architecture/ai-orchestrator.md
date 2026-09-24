# AI Orchestrator

Master spec §64, §66, §78–80, §82.

## Role

Translate natural language into Application Tools and orchestrate
cross-application workflows (§64):

```
Chat → File Manager (find video) → Video Editor (analyze/edit)
     → Subtitle Engine → Render → result card back in Chat (§65)
```

The user must not manually navigate every application.

## Contracts

- Every workspace exposes Application Context (§66): application id,
  project id, current document, selection, capabilities, available tools.
- AI calls tools via ToolGateway (validated, permission-gated, audited).
- Tool execution UI in chat is compact: running card with progress →
  ✓ summary → tap expands details (§30).
- Approval for dangerous actions is explicit, never hidden (§31).

## Contextual AI (§78–80)

No giant chat panel inside workspaces. Each workspace offers contextual AI:
floating button, bottom sheet, side panel, inline command, voice, or
selection menu. Example commands: "สรุปหน้านี้" (browser),
"ตัดช่วงเงียบออกทั้งหมด" (video), "ลบพื้นหลัง" (image),
"แก้ error นี้" (code), "หาไฟล์ APK ล่าสุด" (files),
"สร้าง commit จากการแก้ไขชุดนี้" (git).

## Implementation map

- Brains: `LocalChatBrain` (offline-first), `LlmBrain` (tool loop),
  `RoutedChatBrain`, `FallbackChatBrain` in `ai/agents`.
- Retrieval/validation/few-shot/trace: `tools/capability`
  (`ToolRetriever`, `ToolCallValidator`, `FewShotStore`, `ToolTracer`).
- Context: `WorkingSetStore`, chat context chips (§91).
