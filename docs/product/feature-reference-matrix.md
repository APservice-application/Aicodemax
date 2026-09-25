# Feature ↔ Reference Matrix (พัฒนาฟีเจอร์ §39)

`STATUS`: ✅ DONE · 🟡 PARTIAL (works, gaps listed) · ⬜ TODO/backlog · 🔍 PRESENT, reference audit pending.

| Feature | Reference | Our scope (this spec) | Status | Notes |
|---|---|---|---|---|
| Built-in AI model | — (our requirement) | Qwen3-4B embedded, offline, no download | ✅ CP-147 | 2.5GB GGUF via build pipeline; `/no_think` chat |
| Chat template | Qwen3 ChatML | qwen3() + stripThinking | ✅ CP-147 | unit-tested |
| AI chat | ChatGPT/Claude/Gemini | 30-item §6 set | 🟡 | streaming/stop/retry/persist ✅; attach/voice/context-indicator ⬜ |
| Tool Registry + schemas | Cursor/Copilot Agent | 38 tools w/ name/desc/params/returns/errors | ✅ CP-147 | `StandardToolSchemas`; runnable-gated |
| Agent loop | modern agent systems | plan→execute→observe→verify→recover | 🟡 | verify hook ✅; LLM re-plan ⬜ |
| URL intelligence | Chrome omnibox | UrlResolver (never `http://word`) | ✅ CP-147 | unit-tested incl. Thai |
| Intent split | Chrome omnibox | URL/SEARCH/COMMAND + Thai verbs | ✅ CP-147 | unit-tested |
| Search engines | Chrome settings | Google/Bing/DDG/Brave/custom | ✅ CP-147 | Google default; UI switcher ⬜ |
| Browser tabs | Chrome | new/close/switch/name/note | 🟡 | setup sheet ✅; groups/reorder/persist ⬜ |
| Browser navigation | Chrome | back/forward/reload/stop/home | 🟡 | UI WebView ✅; AI tools (back/forward/reload) ⬜ |
| Bookmarks | Chrome | add/edit/delete/folder/move/search/import/export | 🟡 | add/remove/list ✅; folders/import/export ⬜ |
| History | Chrome | search/open/delete/range/clear | 🟡 | list/search/clear ✅; range delete ⬜ |
| Downloads | Chrome | dl/progress/pause/resume/cancel/retry/open/share | 🟡 | basic download ✅; pause/resume ⬜ |
| Incognito | Chrome | private tabs/session/storage | ⬜ | backlog |
| Find/zoom/desktop | Chrome | find, zoom, desktop site | ⬜ | backlog |
| Permissions | Chrome | per-site camera/mic/geo/... | ⬜ | backlog |
| Browser settings | Chrome settings | general/appearance/privacy/downloads/advanced | ⬜ | backlog |
| AI browser tools | (our agent req) | 22 tools bound to real browser | 🟡 | 10/22 bound, rest schema-only + honest unbound |
| Login handoff | (our agent req) | AUTH_REQUIRED→user→AUTH_COMPLETE | 🟡 | probeLogin ✅; handoff protocol ⬜ |
| Code editor | VS Code/Cursor | explorer/tabs/search/palette/AI edit | 🔍 | files+editor capabilities exist; UX audit pending |
| File manager | Google Files | browse/search/sort/share + AI ops | 🔍 | files.* capabilities exist; UX audit pending |
| Projects | VS Code/Notion | projects/notes/tasks/AI context | 🔍 | present; UX audit pending |
| Git/GitHub | GitHub | repo/branch/diff/commit + AI ops | 🔍 | git.* capabilities exist; UX audit pending |
| Terminal | Windows Terminal/Termux | sessions/history + AI tool | 🔍 | PTY+terminal exist; audit pending |
| Media gallery | Google Photos | browse/albums/share + AI | 🔍 | media capabilities exist; audit pending |
| Video editor | CapCut/DaVinci | timeline/tracks/export + AI plan | 🔍 | video capabilities exist; scope audit pending |
| Image editor | Photoshop/Canva | layers/adjust/export + AI | 🔍 | image capabilities exist; scope audit pending |
| Audio editor | Audacity/Audition | waveform/cut/mix/export + AI | 🔍 | audio capabilities exist; scope audit pending |
| Automation | Zapier/Make | trigger/action/schedule + AI build | ⬜ | backlog |
| Cloud storage | Drive/Dropbox | upload/download/sync/offline | ⬜ | backlog |
| Notes/workspace | Notion | pages/blocks/tasks | 🔍 | present; audit pending |
| Project mgmt | Linear/Notion | tasks/status/labels + AI | 🔍 | present; audit pending |
| Global search | Spotlight | chats/files/tabs/tools/settings | ⬜ | backlog |
| Notifications | Android | task/download/auth deep-links | 🟡 | basic; deep-link audit pending |
| Settings | Android/Chrome | 14-section tree | 🟡 | present; browser/AI sections partial |
| Model manager | Hugging Face | installed/info/switch/import/test | 🟡 | optional-model mgmt ✅; built-in separate ✅ |
| Auth | Google/GitHub OAuth | login/session/re-auth, AI-aware states | ⬜ | backlog |

Rule (§40): anything not ✅/fully-🟡-documented is INCOMPLETE — tracked here, not hidden.
