# CHECKPOINT PROGRESS (MASTER_ARCHITECTURE §57–§59)

> สถานะซื่อสัตย์ ณ 2026-09-21 — DONE = ผ่าน Definition of Done ทั้ง 13 ข้อเท่านั้น,
> อย่างอื่นเป็น PARTIAL/TODO. ทำงานทีละ Checkpoint (ห้ามทำหลายตัวพร้อมกัน)

## ปัจจุบัน
- **กำลังทำ: CP-13** (Capability Registry + Gateway — resolver เพิ่งใส่, เหลือผูก gateway เต็มรูป)
- นโยบาย: backfill CP-00..CP-13 ให้ DONE ก่อน แล้วเดินหน้าตามลำดับ (ห้ามกระโดด)

## FOUNDATION
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-00 | Architecture Lock | DONE | `docs/arch/MASTER_ARCHITECTURE.md` ล็อกเป็น Source of Truth #1 |
| CP-01 | Native Android Shell | PARTIAL | แอปรัน+CI สร้าง APK ได้; เหลือ process-death/state-restoration audit |
| CP-02 | Core State + Event Bus | DONE | `core/state` + เทส |
| CP-03 | Core Data Layer | PARTIAL | checkpoint/audit/memory/conversations/settings จริง; เหลือ backup/restore |
| CP-04 | Resource Manager | PARTIAL | monitor จริง; เหลือ precheck/allow-defer-deny decisions |
| CP-05 | Permission + Security | PARTIAL | autonomy gate + audit + PromptGuard + CommandRisk; เหลือ PermissionManager เต็มรูป (ALLOW ONCE/FOR TASK/DENY + WHAT/WHY/SCOPE/RISK UI) |

## AI CORE
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-06 | Native/Local AI Runtime | TODO | ยังไม่มี LLM จริง (ใช้ rule-based bootstrap) |
| CP-07 | Model Manager | PARTIAL | registry; เหลือ install/download/pause/health-check |
| CP-08 | Model Router + Fallback | PARTIAL | FallbackRouter v0; เหลือ routing ตาม RAM/cost/health |
| CP-09 | Context + Memory | PARTIAL | MemoryStore จริง; เหลือ ContextEngine (fit/compress/scope) |
| CP-10 | AI Orchestrator | PARTIAL | pipeline ไฟล์/git/browser จริง; เหลือ loop เต็ม OBSERVE→VERIFY→RECOVER |
| CP-11 | Task Engine | PARTIAL | states หลัก + cancel/retry; เหลือ WAITING_*/PAUSED/BLOCKED + ข้อมูล Task ครบ §12 |
| CP-12 | Agent Runtime | PARTIAL | LocalAgentRunner; เหลือ specialist agents + allowlist ต่อ agent |

## CAPABILITY PLATFORM
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-13 | Capability Registry + Gateway | PARTIAL | registry+gateway+resolver(native-first) จริง; เหลือ gateway ผูก resolver + capability metadata ครบ §30 |
| CP-14 | Coding Runtime Foundation | TODO | — |
| CP-15 | Language Runtime Manager | TODO | Python/Java/Kotlin/JS ยังไม่มี |
| CP-16 | Dependency Resolver | TODO | — |
| CP-17 | Package Manager | TODO | — |
| CP-18 | Code Engine | PARTIAL | editor set/save จริง; เหลือ patch/diff/apply/reject/rollback |
| CP-19 | File Engine | PARTIAL | browse/open/create/rename/copy/move/delete จริง; เหลือ search/archive/metadata |
| CP-20 | Project Engine | TODO | ยังไม่มี project container (§20) |
| CP-21 | Build Engine | TODO | มีแค่ descriptor; เหลือ pipeline §21 + Adapter |
| CP-22 | Test Engine | TODO | มีแค่ descriptor; เหลือ runners + รายงาน PASS/FAIL/SKIP/BLOCKED |
| CP-23 | Debug Engine | TODO | — |
| CP-24 | Git Engine | PARTIAL | JGit: init/status/log/stage/commit; เหลือ push/pull/branch/merge/stash/conflict |
| CP-25 | GitHub Engine | TODO | — (Secret ผ่าน SecretManager เมื่อทำ) |
| CP-26 | Browser Engine | PARTIAL | tabs/sessions/navigate/WebView; เหลือ history/bookmark/download/handoff |
| CP-27 | Artifact Engine | TODO | — |
| CP-28 | Verification Engine | PARTIAL | RuleVerifier + per-op read-back; เหลือ verify ต่อ engine ตาม §33 |
| CP-29 | Recovery Engine | PARTIAL | retry/cancel; เหลือ ladder §34 (repair/switch/restore) + ห้าม retry ไม่จำกัด |
| CP-30 | Checkpoint/Rollback | PARTIAL | CheckpointStore จริง; เหลือ rollback/restore เต็มรูป |

## COMPATIBILITY (ตั้งใจอยู่หลัง native — §58)
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-31 | Compatibility Engine | PARTIAL | TerminalToolExecutor + CommandRisk gate; เหลือ CompatEngine ครอบ CLI adapter |
| CP-32 | CLI Adapter | PARTIAL | TerminalPort contract; เหลือ Termux PTY runtime (ทำบน dev machine) |
| CP-33 | Optional Terminal / Dev Workspace | TODO | หน้าจอ terminal สำหรับ developer |

## USER EXPERIENCE
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-34 | Chat UI | PARTIAL | แชท+drawer; เหลือ markdown/task cards/pause-stop |
| CP-35 | Home + Navigation | PARTIAL | bottom nav 5 + routes; เหลือ global workspace ครบ §39 |
| CP-36 | Project Workspace | TODO | tabs Overview/Files/Chat/Tasks/Build/Test/Git + restore |
| CP-37 | AI Activity Center | PARTIAL | task list + cancel/retry; เหลือ pause/approve/details/take-control |
| CP-38 | Models UI | PARTIAL | ModelsScreen พื้นฐาน |
| CP-39 | Tools UI | PARTIAL | ToolsScreen ตาม registry จริง; เหลือ install/repair/test actions |
| CP-40 | Agents UI | TODO | — |
| CP-41 | Browser UI | PARTIAL | tabs+WebView; เหลือ back/forward/history/bookmarks |
| CP-42 | File Manager UI | PARTIAL | browse+view; เหลือ grid/search/sort/preview เต็ม §44 |
| CP-43 | Editor UI | TODO | มีแค่ dialog ดูไฟล์; เหลือ editor จริง §43 |
| CP-44 | Build/Test UI | TODO | — |
| CP-45 | Git/GitHub UI | PARTIAL | status line + init; เหลือ branches/diff/commit/push/pull UI |
| CP-46 | Settings + Security UI | PARTIAL | settings พื้นฐาน; เหลือ §52 ครบ + permission UI |
| CP-47 | Cross-Tool Context | TODO | — |
| CP-48 | Offline + Low Resource Mode | TODO | — |
| CP-49 | Error/Empty/Loading/Permission UX Audit | TODO | — |

## FINAL INTEGRATION
| CP | ชื่อ | สถานะ |
|----|------|--------|
| CP-50 | Security Audit | TODO |
| CP-51 | Architecture Duplication Audit | TODO |
| CP-52 | Integration Test | TODO |
| CP-53 | Stress + Low RAM + Restart Test | TODO |
| CP-54 | Release Candidate Audit | TODO |
| CP-55 | Final Architecture Sign-Off | TODO (เจ้าของเซ็น) |

## กฎการอัปเดตไฟล์นี้
- อัปเดตทุกครั้งที่ Checkpoint เปลี่ยนสถานะ (พร้อม commit)
- DONE ได้เมื่อผ่าน §59 ครบ 13 ข้อ + ไม่มีงานค้างของ CP นั้น
- ห้ามทำหลาย CP พร้อมกัน — ทำทีละตัวตาม §57
