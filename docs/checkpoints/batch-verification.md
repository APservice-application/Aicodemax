# Batch Verification (CP-138)

Master spec §100 step 8. Every as-is screen interaction re-verified against the rebuilt UI.

## Verdicts (per screen)

- SCR-001 Home (REMOVED): new-chat → drawer “＋ แชทใหม่” ✓;
  global search → search route (adds conversation search) ✓;
  status cards → launcher + drawer system section ✓;
  machine status line → chat “สถานะระบบ” intent (same data) ✓.
- SCR-002/002A Chat + drawer: all preserved (send/stop/retry/mic/speak/
  task-link) + bubbles/attachments/tool-cards/model-sheet/context-chips/
  rename ✓. Start destination = chat ✓.
- SCR-003/003A Files + editor: folder drill/search/sort/grid ✓;
  multi-select copy/move/rename/delete + details + mkdir are NEW ✓;
  editor save + unsaved guard + AI actions ✓; jump tabs → AI handoff ✓;
  restore tab kept ✓.
- SCR-004 Tasks: pause/resume/cancel/retry/approve/take-control/details ✓;
  filter chips NEW ✓.
- SCR-005 Models, 006 Tools, 007 Settings, 008 About, 009 Audit, 012 Agents,
  014 Build, 015 Skills, 016 Render, 018 Templates, 022 Memory: untouched
  engines, auto-themed; settings gained section headers; tools mapping
  updated (image→แต่งรูป, audio→ตัดเสียง) ✓.
- SCR-010 Browser: tabs/history/bookmarks/WebView/handoff ✓;
  overview grid + downloads + AI panel + ⋮ menu NEW ✓; offline notice ✓.
- SCR-011 Terminal: sessions/exec kept, dark console theme + clear + AI ✓.
- SCR-013 Git: status/commit/branch/diff/push/pull/GitHub kept; reorganized
  into Changes/Commits/Branches/History/Sync tabs + conflicts + AI message
  assist ✓.
- SCR-017 Timeline: engine untouched; wrapped in video shell
  (Projects/Editor/Export) + project preselect ✓.
- SCR-019 Gen: all 5 sections kept as tabs + OutputBlock ✓.
- SCR-020 Record: record/prompter/camera/podcast kept as tabs ✓.
- SCR-021 Subtitle: make/parse/shift/translate/burn kept as tabs ✓.
- SCR-GLB Approval: WHAT/WHY/SCOPE/RISK + 3 decisions kept, 48dp + labels ✓.
- NEW routes: image (9/9 image.* ops), audio (15/15 audio.* ops) ✓.
- UiMode SIMPLE/PRO: re-wired — SIMPLE filters launcher to core apps
  (was dead after Home removal) ✓.

## State checklist (§59/§60/§67)

- Loading skeletons: chat sending, search, browser tabs ✓.
- Empty states: conversations, files, history, downloads, tasks,
  projects, tabs ✓.
- Error + retry: files load, search, git ops, browser download,
  tool calls (message + retry where applicable) ✓.
- Offline: OfflineNotice (browser, search) driven by real
  ResourceMonitor.networkAvailable ✓; git sync errors explain offline ✓.
- Permission: mic rationale + settings path (chat); record errors
  surface from the engine ✓.
- Confirmation: delete chat/file, discard unsaved edits, revoke-all ✓.
- Back: drawer closes first (system); destructive exits guarded ✓.

## Gate criteria (migration checkpoint)

- [x] Every as-is ACT ID still reachable (tables below, mapped per screen).
- [x] Start destination = chat; no dashboard route.
- [x] All screens define applicable states (see checklist above).
- [x] kc green + CI green on both branches per CP.
- [x] dp/sp + WindowInsets; 48dp targets on key actions.
- [x] No engine removed without replacement (all gateway ops kept).

## SCR-001 — Home → REMOVED → search route + drawer + launcher

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2 | C02 เริ่มแชทใหม่ | Tap | newChat(); navigate | SCR-002 |
| 4 | C04 ค้นหา | Tap | gateway 3 calls; render C05 (busy disables) | Same screen |
| 7,12 | C07–C12 card buttons | Tap | navigateSingle(route) | SCR-003/004/005/006/009/010/011/012/013/014/016/017/018/019/020/021/022 |

## SCR-002 — AI Chat → chat

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 ☰ | Tap | drawer open | SCR-002A |
| 2 | C02 ＋ แชทใหม่ | Tap | new conversation | Same screen (empty) |
| 3 | C03 suggestion | Tap | send(text) | Same screen |
| 5 | C05 🔊 | Tap | speak(message.text) | Same screen |
| 9 | C09 ลองใหม่ | Tap | retryLast() with RETRY phase | Same screen |
| 10 | C10 เปิดดู | Tap | navigate | SCR-004 |
| 12 | C12 mic | Tap | permission → listen → prefill composer | Same screen |
| 13 | C13 ส่ง | Tap/IME Send | send → THINKING → RUNNING_TOOL? → READY | Same screen |
| 13 | C13 หยุด | Tap | stop(): cancel job+task+voice+generation | Same screen |

## SCR-002A — Conversation drawer → shell drawer

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2 | C02 ＋ แชทใหม่ | Tap | close + newChat | SCR-002 empty |
| 4 | C04 item | Tap | openConversation(id) + close | SCR-002 |
| 5 | C05 ลบ | Tap | deleteConversation(id) | Same drawer |

## SCR-003 — Projects (workspace) → projects (files workspace)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 5 | C05 สร้าง | Tap | projects.create(name) | Same tab |
| 6 | C06 row | Tap | setActive + workingSet.setProject | Same tab |
| 7 | C07 ขึ้นบน | Tap | load(parent) | Same tab |
| 8,9 | C08/C09 | Tap | cycle sort / toggle grid | Same tab |
| 10,11 | C10 retry / C11 รีเฟรช | Tap | load(path) | Same tab |
| 12,13 | C12/C13 dir | Tap | load(entry.path) | Same tab |
| 12,13 | C12/C13 file | Tap | open editor | SCR-003A |
| 15 | C15 ค้น | Tap | files.search | Same tab |
| 17 | C17 jumps | Tap | onOpen(route) | SCR-002/004/013/014 |
| 20 | C20 ดู | Tap | loadLatest(taskId) | Same tab |

## SCR-003A — File editor dialog → editor dialog (unsaved guard)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 3 | C03 บันทึก | Tap | setContent + save | Same dialog (stays open) |
| 4 | C04 ปิด / outside tap | Tap | close(path) + onClose + reload list | SCR-003 Files |

## SCR-004 — Tasks → tasks (+filters)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 4 | C05 รายละเอียด | Tap | expand/collapse | Same screen |
| 5,10 | C06–C11 | Tap | task action; list refreshes on TaskUpdated | Same screen |

## SCR-005 — Models + Runtime → models

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2 | C02 ติดตั้ง+โหลดโมเดลหลัก | Tap | installActiveModel; progress in C04 | Same screen |
| 3 | C03 กู้คืน | Tap | recover() | Same screen |
| 7,8 | C07/C08 | Tap | installer action | Same screen |
| 11,12,13 | C11/C12/C13 | Tap | detect/connect/disconnect LLM | Same screen |

## SCR-006 — Tools → tools

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2 | C02 เปิด | Tap | navigateSingle(mapped route) or nothing | Mapped SCR / same |
| 3 | C04 ดูชั้น | Tap | expand/collapse | Same screen |
| 4 | C05 ทดสอบ | Tap | selfTest → verdict | Same screen |

## SCR-007 — Settings → settings

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1,2,3 | C01/C02/C03 radio | Tap (row or button) | persist setting | Same screen |
| 6 | C06 ล้างสิทธิ์ทั้งหมด | Tap | revokeAll; shows ล้างสิทธิ์ทั้งหมดแล้ว | Same screen |
| 7 | C07 ดู audit | Tap | navigate | SCR-009 |
| 8 | C08 เกี่ยวกับ | Tap | navigate | SCR-008 |

## SCR-008 — About → about

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|

## SCR-009 — Audit → audit

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C02 รีเฟรช | Tap | queryFiltered(actor, deniedOnly, 100) | Same screen |
| 2,3 | C03/C04 | Tap | set filter + reload | Same screen |

## SCR-010 — Browser → browser (overview/downloads/AI)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 mode | Tap | switch mode | Same screen |
| 10 | C02 ล้างประวัติ | Tap | clear + reload | Same screen |
| 11,13 | C03/C04 row | Tap | go(url) → TABS mode | Same screen |
| 13 | C04 ลบ | Tap | removeBookmark + reload | Same screen |
| 2 | C06 title/✕ | Tap | select / close tab | Same screen |
| 3 | C07 ＋ แท็บใหม่ | Tap | openTab(duckduckgo.com) | Same screen |
| 4 | C08 ‹/› | Tap | WebView back/forward | Same screen |
| 5,8 | C09 IME Go / C12 ไป | Go/Tap | openTab or navigate | Same screen |
| 6 | C10 ☆ | Tap | addBookmark | Same screen |
| 7 | C11 🤖 | Tap | workingSet.handPrompt + navigate | SCR-002 |

## SCR-011 — Terminal → terminal (themed)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2,3 | C02 IME Go / C03 รัน | Go/Tap | exec → output [exit N] / ❌ CODE: msg | Same screen |
| 5 | C05 ＋ เซสชันใหม่ | Tap | openSession → active | Same screen |
| 6 | C06 select/✕ | Tap | select / close + refresh | Same screen |

## SCR-012 — Agents → agents

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|

## SCR-013 — Git → git (tabs)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2 | C02 Init repo | Tap | ensureRepo + load | Same tab |
| 4 | C04 Commit | Tap | stageAll+commit; clears field | Same tab |
| 5 | C05 สร้าง | Tap | createBranch; clears field | Same tab |
| 6 | C06 Diff/Push/Pull | Tap | git op; error line on fail | Same tab |
| 9 | C09 โหลด | Tap | repo()+listIssues() | Same tab |
| 11 | C11 สร้าง issue | Tap | createIssue; clears + reload | Same tab |

## SCR-014 — Build & Test → build

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 รัน build | Tap | build + loadArtifacts | Same screen |
| 2 | C02 รัน test | Tap | runAll(empty suites) | Same screen |
| 3 | C03 วัดความเร็ว | Tap | debug.bench | Same screen |

## SCR-015 — Skills → skills

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 ＋ นำเข้าไฟล์ | Tap | GetContent picker → import | System picker → same screen |
| 4 | C04 ปิด | Tap | hide viewer | Same screen |
| 5 | C05 ดู | Tap | skills.get → viewer | Same screen |
| 5 | C05 ลบ | Tap | skills.remove + reload | Same screen |

## SCR-016 — Render → render

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1,2 | C01/C02 | Tap | cycle project / select preset | Same screen |
| 3,7,10,13,15,18 | C03–C07, C10–C13, C15–C18 | Tap | runCall: op + refreshJobs/History; message | Same screen |

## SCR-017 — Timeline → timeline (video shell)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1,2 | C01/C02 | Tap | cycle project / undo / redo | Same screen |
| 4,6 | C04 ลบ / C06 เพิ่ม | Tap | removeText / addText | Same screen |
| 5,7,8 | C05/C07-kind/C08-mode | Tap | cycle local option | Same screen (no backend) |
| 7,8 | C07 คิดไอเดีย / C08 วางแผนคลิป | Tap | local text generation | Same screen (no backend) |
| 9 | C09 row | Tap | select clip (●) | Same screen |
| 10,21 | C10–C21 | Tap (busy disables) | media.* or gateway call; message | Same screen |
| 22,23,24 | C22/C23/C24 action | Tap | gateway/media op | Same screen |

## SCR-018 — Templates → templates

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 สลับ | Tap | next project (+loadAssets) | Same screen |
| 3 | C03 chip | Tap | set category (local) | Same screen |
| 4 | C04 บันทึกเทมเพลต | Tap | saveTemplate; clears name + reload | Same screen |
| 5 | C05 ใช้/ลบ | Tap | applyTemplate / deleteTemplate | Same screen |
| 6 | C06 ค้น/เพิ่มเข้าคลัง | Tap | librarySearch / libraryAdd | Same screen |
| 7 | C07 ลบ | Tap | libraryRemove + reload | Same screen |

## SCR-019 — Gen (media create) → gen (tabs)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 3,7,8 | C03/C07 chips, C08 robot | Tap | local toggle | Same screen |
| 4,6,7,8,9,10,12 | C05/C06/C07-plan/C08-fx/C09/C10/C12 | Tap (busy disables) | gateway call → message (+lastDst audio) | Same screen |
| 11 | C11 นำเข้าโปรเจกต์ | Tap | importAsset | Same screen |

## SCR-020 — Record → record (tabs)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 2,3 | C02/C03 | Tap | recordStart/recordStop + import | Same screen |
| 4 | C04 toggle | Tap | prompting on/off (local scroll) | Same screen |
| 5 | C05 | Tap | system camera → import | System camera → same screen |
| 6 | C06 ทำพอดแคสต์ | Tap | audio.podcast | Same screen |

## SCR-021 — Subtitle → subtitle (tabs)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 4 | C04 toggles | Tap | local select | Same screen |
| 1,2,3,5,6 | C01/C02/C03/C05/C06 action | Tap (busy + input guards) | gateway subtitle.* → message | Same screen |

## SCR-022 — Memory → memory

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 1 | C01 จำไว้ | Tap (enabled !busy && key && value) | memory.save | Same screen |
| 2 | C02 ทวน | Tap (enabled !busy && key) | memory.recall | Same screen |
| 3 | C03 โหลดบทเรียน | Tap (enabled !busy) | memory.lessons | Same screen |

## SCR-GLB — AI permission dialog (global) → approval dialog (restyled)

| No. | Component | Tap | Result | Destination |
|---|---|---|---|---|
| 3,4,5 | C03/C04/C05 | Tap | approvals.decide(id, decision); next pending shows | Same screen (dialog closes or next) |
