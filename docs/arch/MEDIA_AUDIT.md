# Old-App Audit (§28–29 of ฟีเจอร์ตัดต่อ.txt)

Method: apktool 2.12.1 decode of `com.aiopen.maxcode` (42MB, arm64) + smali/manifest/asset
inspection. No source available — behavior inferred from class/method/strings (honest limit).

## 28 Questions (§29)

1. **มีอะไรอยู่แล้ว**: ai, localai, memory, agent, multiagent, video, project, skill,
   voice, supabase, webai, browser, buildsystem, git, terminal, background, cloud,
   longrun, storage, verification, artifact, recovery, home, ui + FfmpegVideoEngine,
   VideoEditingAgent (Thai system prompt), VideoTools (7 AI tools), SkillManager,
   OpenAiCompatAdapter (vision-capable), SupabaseTools (5 AI tools), WebAiBridgeServer
   (localhost HTTP), MultiAgentCoordinator, LocalAiManager, BackgroundTaskManager.
2. **ฟีเจอร์ไหนใช้ได้**: video analyze/cut/concat/silence-resize/proxy/thumbnail/music-mix/
   text-overlay/export, project store, skills (.md/.txt/.zip + `=== SKILL: id ===`
   injection), OpenAI-compat chat+vision, Supabase auth/rest/rpc/sql/insert tools,
   multi-agent parallel+locks, localhost tool bridge, agent tool-loop + approval.
3. **ฟีเจอร์ไหนเสีย**: Voice input (manifest ขาด RECORD_AUDIO → SpeechRecognizer ใช้ไม่ได้),
   Termux-dependent paths (NEEDS_TERMUX), DevSimulationTools (ของปลอม — ไม่ใช่เสีย แต่ต้องทิ้ง).
4. **ควรนำกลับมาใช้**: video engine+tools+agent prompt pattern, skills, OpenAI adapter,
   Supabase-as-devtool, multi-agent, localhost bridge, local-runtime-detect pattern.
5. **ฟีเจอร์ไหนซ้ำ (เรามีแล้ว)**: agent loop+allowlist+approval, Git (JGit), prompt guard,
   command risk, Thai intent (บางส่วน), project container, foreground task pattern.
6. **ไม่จำเป็น**: template-only flow (สเปกห้าม), Termux fallback (AMENDMENT-001 ห้ามพึ่ง),
   simulate_* tools (ของปลอม).
7. **Supabase ใช้ทำอะไร**: dev tool ให้ AI ปฏิบัติงานบน backend *ของ user เอง*
   (auth email/pass, REST query, RPC incl. exec_sql, insert) + SupabaseSqlGuard
   (destructive ต้อง confirm). ไม่ใช่ infra ของแอป.
8. **จำเป็นต้องใช้ Supabase หรือไม่**: ไม่จำเป็นสำหรับ media/local (ตาม §24);
   port เป็น *optional dev tool* (user กรอก URL+key เอง, memory-only) — ไม่บังคับ.
9. **Library วิดีโอ**: libffmpeg.so + libffprobe.so (**arm64 อย่างเดียว**), ไม่มี Media3/ExoPlayer.
10. **Library เสียง**: ไม่มี dedicated lib (ffmpeg จัดการ); STT ผ่าน SpeechRecognizer (เสีย);
    ไม่มี TTS.
11. **Library รูป**: ไม่มี local vision — ใช้ cloud vision ผ่าน adapters (ต้อง API key).
12. **มี FFmpeg หรือไม่**: มี — static .so คู่ arm64, FirstRunBootstrapper แตกเป็น executable wrappers.
13. **มี Native Engine หรือไม่**: libtermux.so (PTY, 4 ABIs) + ffmpeg/ffprobe (arm64 only).
14. **มี AI Model อะไร**: ไม่มี bundle — คาดหวัง llama-server/ollama/whisper binaries บนเครื่อง
    (คุยผ่าน 127.0.0.1, honest-degraded ถ้าไม่มี).
15. **มีระบบ Render หรือไม่**: มี (video_render + export records + proxy) แต่ไม่พบ QC เต็มรูป —
    verify แค่ผลลัพธ์ path/size/duration ผ่าน agent prompt.
16. **มีระบบ Timeline หรือไม่**: มีแบบย่อ (clips+overlays+music ใน VideoProject + edit_timeline tool).
17. **มีระบบ Project หรือไม่**: มี (ProjectManager + VideoProjectStore, JSON file-backed).
18. **มีระบบ File Management หรือไม่**: มี (sandbox + list/read/write tools).
19. **มีระบบ Cache หรือไม่**: มีบางส่วน (proxy) — ไม่พบ cache manager/prune ชัดเจน.
20. **มีระบบ Background Worker หรือไม่**: มี (BackgroundTaskManager + LongTaskForegroundService).
21. **ควรนำกลับมาใช้**: ข้อ 4 + Thai NL→tool mapping + non-destructive principle (ไม่แตะ source).
22. **ควรเขียนใหม่**: image/audio engines (ของเก่าไม่มี local), TTS, timeline เต็ม (§9),
    render queue + QC (§19–20), STT ที่ขอ permission ถูกต้อง, in-app PTY (ไม่พึ่ง Termux),
    planner แบบไม่ใช้ template (§27).
23. **ควรลบ**: simulate_*, Termux fallback, template-only flow.
24. **Dependency ปัญหา**: ffmpeg arm64-only (x86/armeabi-7 วิดีโอใช้ไม่ได้), ขาด RECORD_AUDIO,
    THIRD_PARTY ไม่พูดถึง Termux (GPLv3)/JGit (EPL), single-module 31,751 smali files.
25. **เสี่ยง Performance**: dex 44MB (multidex/install ช้า), encode ด้วย software x264
    (ไม่มี HW MediaCodec — ช้า+กินแบต), whisper/llama บนมือถือ.
26. **เสี่ยง Storage**: renders/proxies ไม่มี prune, model downloads ใหญ่.
27. **เสี่ยง Memory**: llama/whisper RAM (strings ของเขาเองเตือน), video buffers.
28. **เสี่ยง Compat**: arm64-only ffmpeg, SpeechRecognizer ต้อง Google services,
    llama binaries เฉพาะเครื่อง.

## Classification (§28)

| Class | Items |
|---|---|
| EXISTING | video/project/skill/voice/supabase/webai/multiagent/localai/background systems |
| REUSABLE | FfmpegVideoEngine API shape, VideoTools (7), agent prompt pattern, Skill format+injection, OpenAiCompatAdapter shape, SupabaseTools+SqlGuard, MultiAgentCoordinator, localhost bridge design, Thai NL mapping, 155 Thai keywords |
| REPLACE | voice input (add permission), template flow → AI planner, software-only encode → +HW pref |
| REMOVE | simulate_*, Termux fallback paths |
| MISSING | image/audio local engines, TTS, full timeline, render queue+QC, cache prune |
| BROKEN | voice (permission), x86 video (no ffmpeg .so) |
| DUPLICATED | agent loop, git, guards (already in ours — align, don't double-build) |
| UNNECESSARY | Supabase-as-infra (dev-tool only), templates-as-primary |

## Port plan → extension checkpoints CP-56..CP-70 (see CHECKPOINTS.md)

Order: Thai-155 → Skills → LLM adapter → Voice(+TTS) → Image → Audio → Video(ffmpeg-kit,
arm64+x86_64, HW-pref) → Media assets+Timeline+Project → Planner+Intent → Subtitles →
Render+QC+Preview+Export → Supabase devtool → WebAI/multiagent → integration+release.
Every engine ships as: Port + Runtime + Descriptor + Gateway executor + capability
bindings (§30 metadata) + UI + tests — automation-usable by the AI, no hard-coded
workflows, user approves the final result.
