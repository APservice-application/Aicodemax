# UI Migration Checkpoint

Master spec §100–101. Batch verification gate for the UI rebuild.

## Order (§100)

1. Audit existing implementation — DONE (`design/as-is-inventory`,
   `EXISTING_APPLICATION_UI_UX_INVENTORY.pdf`, 25 screens / 237
   components / 96 interactions).
2. UI reference documentation — DONE (`docs/ui-reference/*.md`).
3. Screen specifications — DONE (`docs/ui/*.md` + `screen-inventory.md`).
4. Component/state specifications — DONE (`docs/ui/design-system.md`
   tokens + state matrices).
5. Application shell — CP-135 (start=chat, drawer+launcher, tokens,
   shared components; dashboard removed).
6. Migrate engines into workspaces — CP-136 (browser/video/image/audio),
   CP-137 (files/dev/git/models/memory/settings/tasks + states).
7. Connect AI orchestrator — contextual AI per workspace + tool cards +
   approval + result return (part of CP-135–137).
8. Batch verification — CP-138: §103 checklist per screen, §59/§60/§67
   states, back chains, unsaved dialogs, no feature loss vs as-is
   inventory (96 ACT IDs mapped).

## Gate criteria

- [ ] Every as-is ACT ID still reachable (map old→new in this file).
- [ ] Start destination = chat; no dashboard route.
- [ ] All screens define INITIAL/LOADING/READY/EMPTY/ERROR/OFFLINE/
      PERMISSION/CONFIRMATION where applicable.
- [ ] kc-build green + CI green on both branches per CP.
- [ ] No hardcoded pixels; dp/sp + WindowInsets only (§72).
- [ ] No removed engine without replacement (§81).

## Old → new route map (fill during migration)

- home → (removed; global search → SCR-SHELL-004)
- chat → SCR-CHAT-001 · projects → files+dev workspaces ·
  tasks → SCR-SYS-004 · models → SCR-SYS-001 · tools → launcher ·
  settings → SCR-SYS-006 · about → settings/about · audit → SCR-SYS-005
- browser → SCR-BROWSER-* · terminal → SCR-DEV-004 · agents → tasks
  (agent column) · git → SCR-GIT-* · build → SCR-DEV-005 + tasks ·
  skills → SCR-SYS-003 · render → SCR-VIDEO-009 · timeline → SCR-VIDEO-*
- templates → SCR-VIDEO-003/009 · gen → SCR-GEN-* · record → SCR-REC-* +
  SCR-AUDIO-003 · subtitle → SCR-SUB-* · memory → SCR-SYS-002
