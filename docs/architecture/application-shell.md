# Application Shell

Master spec §10, §33, §77, §84–85.

## Responsibilities (minimal shell)

Application navigation, global AI entry, permissions, notifications, project
context, system state, application launcher. The shell must NOT force one UI
structure onto every application — each workspace owns its chrome.

## Navigation model

- Start destination: AI Chat (§11). No dashboard, no feature grid.
- Launcher lives behind ☰ (drawer → APPLICATIONS section, §32/§77).
- Opening an app: Launcher → workspace transition 150–250ms (§33).
- Back respects the workspace (§85): close inspector → close panel → exit
  project → launcher/chat. Browser Back = web history first. Never destroy a
  project on first Back.
- Unsaved changes: [Cancel] [Discard] [Save] dialog (§86). Never lose work.

## Global UI elements

- Chat top bar (56dp): ☰ · title · New Chat · ⋮ (§13).
- Drawer (~300–320dp): brand, + New Chat, search, TODAY/YESTERDAY,
  PROJECTS, APPLICATIONS launcher, Settings (§27/§32).
- Approval surface: inline card in chat [Cancel] [Review] [Approve] (§31)
  plus the global permission dialog for gateway-level approvals.
- Tasks center reachable for background work (§62–63).

## Implementation map

- `AicodeNav`: NavHost, start = chat; per-workspace routes.
- Drawer + launcher: `ui/chat` drawer hosts APPLICATIONS grid.
- Back handling: per-workspace `BackHandler` chains before nav pop.
