# Global Shell UI

Master spec §10–11, §13, §27, §32–33, §77, §85–86.

## Inventory

- SCR-SHELL-001 Chat home (start destination; see `chat.md`).
- SCR-SHELL-002 Navigation drawer (☰): brand, + New Chat, search, TODAY /
  YESTERDAY, PROJECTS, APPLICATIONS launcher grid, Settings. Width
  ~300–320dp.
- SCR-SHELL-003 Application launcher (inside drawer, §32): compact
  rows/grid with icon + name + short description. No giant cards.
- SCR-SHELL-004 Global search (§61): chats, files, projects, applications,
  settings, skills, memory — grouped by type.
- SCR-SHELL-005 Unsaved-changes dialog: [Cancel] [Discard] [Save] (§86).
- SCR-SHELL-006 Background-task indicator (§62): "AI working…" entry to Tasks.

## Rules

- Start = chat. No dashboard (§11, §77).
- Workspace transition 150–250ms, no flashy animation (§33).
- Back chain per workspace (§85); Browser Back = web history.
- Long operations off UI thread; state survives app switching (§88–89).
