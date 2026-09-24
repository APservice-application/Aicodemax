# Application Platform

Master spec: `การปรับ ui ครั้งใหญ่.txt` §1, §64–66, §82, §84, §88–90.

## Concept

Aicodemax = AI Application Platform. AI Chat is the home application; real
application workspaces (Browser, Video/Image/Audio editors, Files,
Development, Git, Subtitle, Recorder, Generator) live in the same shell and
share context through the AI orchestrator. Not a dashboard of cards.

## Layers (separation rule, §82)

```
UI (Compose screen)
  ↓
Application Workspace Controller (per-app state + navigation)
  ↓
Application Engine (existing engines: media, browser, git, … — §81 preserved)
  ↓
Tool Layer (ToolGateway contracts — capabilities, validation, audit)
  ↓
AI Orchestrator (ChatBrain / LlmBrain / LocalAgentRunner)
  ↓
Runtime (AiRuntimeManager + local/remote providers) + Storage
```

AI drives applications through Tool Contracts, never by poking UI (§82).
UI automation (accessibility/DOM/click/scroll/type) is allowed only where no
API exists (§83); auth/OTP/CAPTCHA always need a human.

## Ownership (§84)

- Each Application owns: UI, in-workspace navigation, editor state, tool
  panel, context, undo/redo.
- Global Shell owns: application switching, AI surface, permissions,
  notifications, global project context.

## Application lifecycle (§89)

Active → Background → Suspended → Released. Workspace state (browser tabs,
video project, editor buffers) must be saved before release and restored on
return. Never keep every application fully alive.

## Project context (§90)

A project may bundle files, media, code, models, chats, tasks, memory, AI
context, git repo. AI must understand it without the user re-attaching every
file. Chat context chips expose attached project/files/images/URL (§91).

## Result return (§65)

When an application finishes, Chat receives: ✓ completed + artifact +
[Preview] [Open in X] [Open file] [Share]. Workspaces are independent but
share context through the platform.
