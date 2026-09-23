# CHECKPOINT PROGRESS (MASTER_ARCHITECTURE §57–§59)

> สถานะซื่อสัตย์ ณ 2026-09-21 — DONE = ผ่าน Definition of Done ทั้ง 13 ข้อเท่านั้น,
> อย่างอื่นเป็น PARTIAL/TODO. ชุดเทส local: **397/397** (`kc-build.sh` + JUnit)

## ปัจจุบัน
- **งาน code เสร็จทุก CP ที่ทำได้บนเครื่องนี้** — เหลือ TODO/PARTIAL ที่ต้องการ
  (ก) ไฟล์โมเดล (CP-06), (ข) อุปกรณ์/NDK จริง (CP-01/32), (ค) เจ้าของเซ็น (CP-55),
  (ง) specialist agents (CP-12)
- สาขา: `release/foundation-p0-p30` → merge ลง `main` เมื่อ CI เขียว
- **ส่วนต่อขยาย Creative/Automation (CP-56..CP-70)** ตาม `ฟีเจอร์ตัดต่อ.txt` +
  คำสั่งเจ้าของ "เอาของเก่ามาทั้งหมดที่เข้ากับสถาปัตยกรรม" — กำลังทำตามลำดับ

## FOUNDATION
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-00 | Architecture Lock | DONE | `docs/arch/MASTER_ARCHITECTURE.md` Source of Truth #1 |
| CP-01 | Native Android Shell | PARTIAL | แอปรัน+CI สร้าง APK ได้; เหลือ process-death/state-restoration audit (ต้องอุปกรณ์จริง) |
| CP-02 | Core State + Event Bus | DONE | `core/state` + เทส |
| CP-03 | Core Data Layer | DONE | checkpoint/audit/memory/conversations/settings/backup-restore จริง + เทส |
| CP-04 | Resource Manager | DONE | monitor + precheck ALLOW/DEFER/DENY + ResourceModes + เทส |
| CP-05 | Permission + Security | DONE | PermissionManager (ONCE/FOR TASK/DENY) + WHAT/WHY/SCOPE/RISK dialog + RiskAdvisor + เทส |

## AI CORE
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-06 | Native/Local AI Runtime | TODO | ยังไม่มีไฟล์โมเดล — ใช้ bootstrap (extraction/summarizer) ซื่อสัตย์ ไม่แกล้งมี LLM |
| CP-07 | Model Manager | DONE | registry + install/download/pause/health-check + เทส |
| CP-08 | Model Router + Fallback | DONE | ScoringRouter (RAM/cost/health) + FallbackRouter + เทส |
| CP-09 | Context + Memory | DONE | ContextEngine (fit/compress/scope) + MemoryStore/Engine + เทส |
| CP-10 | AI Orchestrator | DONE | loop OBSERVE→VERIFY→RECOVER + pipeline จริง + เทส |
| CP-11 | Task Engine | DONE | states ครบ (WAITING_*/PAUSED/BLOCKED) + Task ครบ §12 + pause/cancel/retry + เทส |
| CP-12 | Agent Runtime | PARTIAL | LocalAgentRunner + allowlist ต่อ agent + Agents UI; เหลือ specialist agents ตัวที่ 2+ |

## CAPABILITY PLATFORM
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-13 | Capability Registry + Gateway | DONE | §30 metadata ครบ 37 bindings + state mapping + resolver (native-first) + `callCapability` + เทส |
| CP-14 | Coding Runtime Foundation | DONE | `tools/runtime` + เทส |
| CP-15 | Language Runtime Manager | DONE | Python/Java/Kotlin/JS runtimes (graceful-degraded) + เทส |
| CP-16 | Dependency Resolver | DONE | resolver + เทส |
| CP-17 | Package Manager | DONE | install/list + เทส |
| CP-18 | Code Engine | DONE | set/save/preview/patch/diff + inverse patch + เทส |
| CP-19 | File Engine | DONE | browse/open/create/rename/copy/move/delete/search/archive/metadata + เทส |
| CP-20 | Project Engine | DONE | project container §20 + เทส |
| CP-21 | Build Engine | DONE | pipeline + Adapter SPI (Gradle adapter = งานอุปกรณ์จริง) + เทส |
| CP-22 | Test Engine | DONE | runners + รายงาน PASS/FAIL/SKIP/BLOCKED + เทส |
| CP-23 | Debug Engine | DONE | static analysis engine (live debug = งานอุปกรณ์จริง) + เทส |
| CP-24 | Git Engine | DONE | JGit: init/status/log/stage/commit/push/pull/branch/merge/stash/conflict + เทส (+redacted credentials) |
| CP-25 | GitHub Engine | DONE | repo/issues/create + token provider seam (SecretManager = provider ในอนาคต) + เทส |
| CP-26 | Browser Engine | DONE | tabs/sessions/navigate/history/bookmark/download/handoff + เทส |
| CP-27 | Artifact Engine | DONE | store/verify + เทส |
| CP-28 | Verification Engine | DONE | RuleVerifier + per-op read-back + per-engine verify + เทส |
| CP-29 | Recovery Engine | DONE | ladder repair/switch/restore + retry มีขอบเขต + เทส |
| CP-30 | Checkpoint/Rollback | DONE | CheckpointStore + RecoveryManager restore/prune + เทส |

## COMPATIBILITY (ตั้งใจอยู่หลัง native — §58)
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-31 | Compatibility Engine | DONE | CompatEngine ครอบ CLI adapter + CommandRisk gate + เทส |
| CP-32 | CLI Adapter | PARTIAL | TerminalPort contract + executor + honest BLOCKED; เหลือ Termux PTY runtime (ต้อง dev machine + NDK) |
| CP-33 | Optional Terminal / Dev Workspace | DONE | TerminalScreen (BLOCKED ซื่อสัตย์จนกว่า CP-32 เสร็จ) |

## USER EXPERIENCE
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-34 | Chat UI | DONE | แชท + markdown + task cards + stop + pending-prefill |
| CP-35 | Home + Navigation | DONE | bottom nav + project cards + mode line + routes |
| CP-36 | Project Workspace | DONE | tabs Overview/Files/Chat/Tasks/Build/Test/Git + restore |
| CP-37 | AI Activity Center | DONE | task list + pause/approve/details/take-control |
| CP-38 | Models UI | DONE | registry + install/progress/health |
| CP-39 | Tools UI | DONE | registry + self-test + layer table (install/repair N/A — tools คอมไพล์มากับแอป) |
| CP-40 | Agents UI | DONE | list + allowlist (specialists ตาม CP-12) |
| CP-41 | Browser UI | DONE | tabs + back/forward + history/bookmarks + handoff |
| CP-42 | File Manager UI | DONE | browse + grid/search/sort + preview ผ่าน editor |
| CP-43 | Editor UI | DONE | editor จริงบน EditorPort (แบบ dialog) + preview/patch |
| CP-44 | Build/Test UI | DONE | run list + per-run status |
| CP-45 | Git/GitHub UI | DONE | git เต็ม + GitHub tab (repo/issues/create, token memory-only) |
| CP-46 | Settings + Security UI | DONE | theme/autonomy/model + grants + revoke-all + audit link |
| CP-47 | Cross-Tool Context | DONE | WorkingSet + handoff browser→chat + เทส |
| CP-48 | Offline + Low Resource Mode | DONE | ResourceModes + Home mode line + เทส |
| CP-49 | Error/Empty/Loading/Permission UX Audit | DONE | `docs/arch/AUDITS.md` — ทุก route มี empty/loading/error/denied |

## FINAL INTEGRATION
| CP | ชื่อ | สถานะ |
|----|------|--------|
| CP-50 | Security Audit | DONE (`docs/arch/AUDITS.md`, แก้ GitCredentials redaction แล้ว) |
| CP-51 | Architecture Duplication Audit | DONE (`docs/arch/AUDITS.md` — ไม่พบโค้ดซ้ำใน production) |
| CP-52 | Integration Test | DONE (`GatewayIntegrationTest` — pipeline จริง files→editor→git→browser) |
| CP-53 | Stress + Low RAM + Restart Test | DONE (`AuditVolumeTest` 2000 รายการ + rotation; kill/restart บน hardware ยังต้อง manual) |
| CP-54 | Release Candidate Audit | DONE (v0.1.0 foundation GO — ดู `docs/arch/AUDITS.md`) |
| CP-55 | Final Architecture Sign-Off | TODO (เจ้าของเซ็น) |

## ส่วนต่อขยาย CREATIVE/AUTOMATION (สเปก ฟีเจอร์ตัดต่อ.txt)
| CP | ชื่อ | สถานะ | หลักฐาน/เหลือ |
|----|------|--------|----------------|
| CP-56 | Old-app media audit (§28–29) | DONE | `docs/arch/MEDIA_AUDIT.md` — ตอบ 28 ข้อ + ตาราง EXISTING..UNNECESSARY |
| CP-57 | SmartIntent Thai-155 + Dynamic Questionnaire | DONE | ThaiVocabulary 155 คำ + 13 intents + Questionnaire + debug/memory engines ต่อ gateway + เทส (195/195) |
| CP-58 | SkillManager (skill files → context) | DONE | FileSkillStore (.md/.txt/.zip ≤2MB) + gateway 5 actions + Skills UI + intents + เทส (205/205) |
| CP-59 | LLM Provider Adapter (OpenAI-compat + vision) | DONE | OpenAiCompatProvider + LlmBrain ACTION loop + localhost detect + Models UI + เทส (218/218) |
| CP-60 | Voice (STT input + TTS output, TH/EN) | DONE | VoicePort + voice.* gateway + VOICE intents + mic/🔊 UI + เทส (228/228) |
| CP-61 | Image Engine (edit + info, AI tools) | DONE | header probe + pixel ops + image.* gateway + IMAGE intents + เทส (247/247) |
| CP-62 | Audio Engine (edit + analysis, AI tools) | DONE | WAV pipeline + probe + MediaCodec decode + audio.* gateway + AUDIO intents + เทส (269/269) |
| CP-63 | Video Engine (ffmpeg + probe + tools) | DONE | MP4 probe + thumb/trim/extract (MediaCodec/Muxer, no re-encode) + video.* gateway + VIDEO intents + เทส (277/277) |
| CP-64 | Media Assets + Timeline + Project State | DONE | project/asset/timeline/version stores + media.* gateway + PROJECT intents + เทส (291/291) |
| CP-65 | Editing Planner + Intent + NL mapping | DONE | EditingPlanner (preset data §27) + MEDIA_EDIT จริง + สกิล aicode-tools อัปเดต + เทส (296/296) |
| CP-66 | Subtitle Engine (STT→SRT→burn-in, TH/EN) | DONE | SRT ops + YUV + burn transcode (Canvas/AVC ≤720p) + subtitle.* gateway + SUB intents + เทส (310/310) |
| CP-67 | Render Queue + Preview + Export (§19–21) | DONE | คิวเรนเดอร์ + fast/concat transcode + QC + พรีวิว + อนุมัติ + เอ็กซ์พอร์ต + Render UI + เทส (320/320) |
| CP-68 | Supabase Devtool (optional, user keys) | TODO | dev-tool เท่านั้น ไม่ใช่ infra (§24) |
| CP-69 | WebAI localhost bridge + Multi-agent | TODO | 127.0.0.1 + token เท่านั้น |
| CP-70 | Media integration + docs + release | TODO | — |
| CP-71 | Transaction + Undo/Redo + Events + Project mgmt | DONE | transaction+rollback + undo/redo + events + checkpoints + trash/backup + UI + เทส (330/330) |
| CP-72 | Timeline pro ops + markers + track flags | DONE | TimelineOps + split/trim/move/delete/duplicate + markers + lock/mute/hide + render เคารพ mute/hide + CLIP intents + เทส (341/341) |
| CP-73 | Basic video ops + render transform | DONE | ClipTransform + TimelineOps + transform/freeze tools + CLIP intents + render compose + Timeline UI + เทส (351/351) |
| CP-74 | Text engine + AI text | DONE | OverlayText + presets + anim + text tools/ideas + TEXT intents + render overlay + UI + เทส (358/358) |
| CP-75 | Speed engine | DONE | ClipSpeed rate/reverse/curve + speed-aware ops + audio/video render + intents + UI + เทส (364/364) |
| CP-76 | Keyframe engine | DONE | KeyPoint/ClipKeyframes + Easing(linear/ease/bezier) + set/remove/clear + split-shift + render interp (video/still/reverse/audio) + intents + UI + เทส (373/373) |
| CP-77 | Transitions + basic effects | DONE | edge transitions (fade/dissolve/wipe, in-bounds v0) + ClipFx blur/vignette/grain + video/still/reverse/audio render + intents + UI + เทส (381/381) |
| CP-78 | Color engine + scopes | DONE | ClipColor basic+HSL + presets + render grade + histogram scopes (image.scopes + render QC exposure) + intents + UI + เทส (390/390) |
| CP-79 | Mask + chroma + background | DONE | ClipMask rect/ellipse + ClipChroma key/despill + ClipBackground color/image/blur + alpha composite render + intents + UI + เทส (397/397) |
| CP-80 | Tracking-lite (§15) + Stabilize (§43) | DONE | template-match motion path → text-follow/clip keyframes + stabilizer auto-zoom + Human+AI + Undo (CI GREEN) |
| CP-81 | Color complete (§42) | DONE | exposure/whites/blacks + .cube LUT + Auto Color (gray-world+levels, offline) + Human+AI + Undo (CI GREEN) |
| CP-82 | Template Engine (§51–53) | DONE | project-graph templates + slots/replace + searchable asset library + builtins + Human+AI + Undo (CI GREEN) |
| CP-83 | Generative Media (§46) | DONE | provider abstraction + offline poster/background/stylize/TTS → assets + Human+AI (CI GREEN) |
| CP-84 | Ken Burns + Slideshow (§45) | DONE | per-clip motion on stills+video + slideshow builder + Human+AI + Undo (CI GREEN) |
| CP-85 | Beats + Mixer-lite (§31/§102) | DONE | offline BPM/beat-grid + beats→markers + clip volume + Human+AI + Undo (CI GREEN) |
| CP-86 | Voice FX + Synth (§29/§30) | DONE | pitch/robot/echo + procedural music beds + SFX + Human+AI (CI GREEN) |
| CP-87 | Autocut + Highlights (§32/§40) | DONE | offline speech ranges + silence cut + best-window markers (CI GREEN) |
| CP-88 | Reframe + Canvas (§33) | DONE | pan-scan math + subject crop + 9:16/1:1 render canvas (CI GREEN) |
| CP-89 | Photo pro (§37) | DONE | adjust/upscale-bicubic/restore on image tool + Human+AI (CI GREEN) |
| CP-90 | Color pro (§34) | DONE | 3-way wheels + clip-to-clip match + WB presets + Human+AI (CI GREEN) |
| CP-91 | Enhance lite (§35) | DONE | one-click auto grade+sharpen+denoise + single undo (CI GREEN) |
| CP-92+93 | Honest defers: 3D cam-track (§36) + face beauty | DONE | planner explains + alternatives, no fake ML (CI GREEN) |
| CP-94 | Record studio (§54–56) | DONE | in-app audio record + teleprompter + system-camera capture (screen-capture deferred) (CI GREEN) |
| CP-95 | Podcast suite | DONE | mix/normalize/autocut + one-shot podcast pipeline + Human+AI (CI GREEN) |
| CP-96 | 5 AI content modes | DONE | commercial/story/vlog/tutorial/review plans + Human+AI (CI GREEN) |
| CP-97 | Script→video | DONE | beats → TTS+background+captions timeline + Human+AI (CI GREEN) |
| CP-98 | Subtitle translator | DONE | offline Thai↔English dictionary + coverage + Human+AI (CI GREEN) |
| CP-99 | Honest defers: lipsync + AI presenter | DONE | planner explains + alternatives, no fake ML (CI GREEN) |
| CP-100 | Brand kits + packaging + render batch | DONE | brand kits + project .zip + render batch + Human+AI (CI GREEN) |
| CP-101 | Thumbnail pro | DONE | video frame + grade + title cover + Human+AI (CI GREEN) |
| CP-102 | Proxy transcode + cache manager | DONE | MediaCodec downscale + temps cleanup (CI GREEN) |
| CP-103 | GPU partial | DONE | HW encoder pick + hwinfo report + Human+AI (CI GREEN) |
| CP-104 | Multicam sync | DONE | onset alignment + cuts + EDL + Human+AI (CI GREEN) |
| CP-105 | Video scopes | DONE | waveform/vectorscope/parade + Human+AI (CI GREEN) |
| CP-106 | Expert UX | DONE | keyboard shortcuts + TalkBack semantics + Human+AI (CI GREEN) |
| CP-107 | Local AI + router | DONE | routed brain with local-first failover (เทส 547/547, CI GREEN) |
| CP-108 | Cloud stubs | DONE | provider slots + LLM subtitle engine + Human+AI (เทส 553/553, CI GREEN) |
| CP-109 | Perf/benchmark | DONE | device bench + render wall-time + Human+AI (เทส 559/559, CI GREEN) |
| CP-110 | Director review + QC polish | DONE | timeline audit + evendims/bitrate (เทส 568/568, CI GREEN) |
| CP-111 | Modes + search | DONE | AI mode planner UI + global search fan-out (เทส 572/572, CI GREEN) |
| CP-112 | Spec-audit UI closure | DONE | SubtitleScreen ใหม่ + brand/package UI + proxy UI + deep-links + CHECKPOINTS ตามจริง (CI GREEN) |
| CP-113 | Real terminal: system shell in-app | DONE | SystemShellPort (sh จริง + timeout + cap, พอร์ตจากแอปเก่า) + gateway register + RUN_COMMAND ไข prefix + descriptor ซื่อสัตย์ (CI GREEN) |
| CP-114 | LearningEngine (learn from real outcomes) | DONE | observe/promote/persist + flaky warn + resolver prefs + memory.lessons + บทเรียน intent (CI GREEN) |
| CP-115 | Browser page automation (read/click/type/probe) | DONE | AndroidBrowserPort JS bridge + auth boundary + intents อ่าน/คลิก/พิมพ์ในเว็บ (CI GREEN) |
| CP-116 | Visual states (§86–88, §99) | DONE | ShimmerSkeleton/EmptyState/ErrorState + AicodeAnim + ใช้ใน Chat/Projects/Tasks (จอที่เหลือทยอยตาม) (CI GREEN) |
| CP-117 | Memory UI + Simple/Pro modes | DONE | MemoryScreen (จำ/ทวน/บทเรียน) + UiMode setting + Home กรองตามโหมด (CI GREEN) |
| CP-118 | Native toolchain (ffmpeg/llama .so embedded) | DONE | CI fetch + packaging + NativeToolchain detect + debug.native (CI GREEN, .so ยืนยันใน APK) |
| CP-119 | ffmpeg-backed media ops (probe/export) | DONE | Ffmpeg probe/export + media.asset.probe + media.timeline.export (CI GREEN) |
| CP-120 | On-device model (download + llama-server) | DONE | ModelStore + LlamaServer + model.* gateway (CI GREEN) |

## กฎการอัปเดตไฟล์นี้
- อัปเดตทุกครั้งที่ Checkpoint เปลี่ยนสถานะ (พร้อม commit)
- DONE ได้เมื่อผ่าน §59 ครบ 13 ข้อ + ไม่มีงานค้างของ CP นั้น
- ห้ามทำหลาย CP พร้อมกัน — ทำทีละตัวตาม §57
