# Compatibility Matrix (พัฒนาฟีเจอร์ §38)

Every integration needs an ACTUAL connection — UI links alone do not count.
`STATUS`: ✅ connected · 🟡 partial (gaps listed) · ⬜ missing.

| Source | Target | Data/State shared | Tool / route | Status | Gap |
|---|---|---|---|---|---|
| AI | Browser | tabs, page text, login state | 10 bound `browser.*` tools | 🟡 | back/forward/reload/scroll/download tools; UI-active-tab sync |
| Browser | AI | current tab/page for Ask-AI, hand-to-chat | handoff prompt + `browser.*` read tools | ✅ CP-147 | — |
| AI | Files | workspace files | `file.*` → `files.*` capabilities | ✅ | — |
| AI | Code | source files | `code.*` → files/editor capabilities | 🟡 | `code.build`, `code.test` unbound |
| AI | Git | repo state | `git.*` capabilities | ✅ | — |
| AI | Projects | current project context | project state (wired) | 🟡 | reference-UX audit pending |
| AI | Media | assets for edit/summarize | media capabilities | 🟡 | scope audit pending |
| AI | Terminal | command execution | `terminal.exec` capability | 🟡 | UX audit pending |
| AI | Automation | workflows from NL | — | ⬜ | automation system backlog |
| AI | Cloud | cloud files | — | ⬜ | cloud system backlog |
| Browser | Files | downloads → workspace `downloads/` | FileDownloader | 🟡 | pause/resume/progress UI |
| Browser | Projects | save page/file into project | — | ⬜ | backlog |
| Code | Git | edit→diff→commit flow | capabilities | 🟡 | UX audit pending |
| Media | Files | import/export media | media+files capabilities | 🟡 | scope audit pending |
| Media | Projects | project assets | media project capabilities | 🟡 | scope audit pending |
| Notifications | AI tasks | task progress/errors | notification hooks | 🟡 | deep-link audit pending |
| Notifications | Downloads | download state | — | ⬜ | backlog |
| Auth | AI/Browser | AUTH states, login handoff | probeLogin | 🟡 | handoff protocol + re-auth UX backlog |

E2E workflows (§37): TEST A (web→file→AI) 🟡 runnable via bound tools; TEST B/C/D/E ⬜ blocked on missing tools/systems above.
