# Studio Blueprint Gap Audit — `ระบบตัดต่อวีดีโอขั้นสูง.txt` (§0–134)

สถานะเทียบกับ `main=d03b51e` (CP-67 + fix). หลัก: อันไหนมีแล้ว → พัฒนาให้ดีขึ้น; อันไหนไม่มี → ใส่เพิ่ม.
Golden rule §132 ทุก CP: Human ใช้ได้ + AI ใช้ได้ + มี Tool/API + Undo + Transaction + Offline/Cloud ชัดเจน + ลง Project Graph.

## มีแล้ว (พัฒนาให้ดีขึ้นใน CP ที่ระบุ)

| ส่วน | มีแล้ว | ต้องพัฒนา | CP |
|---|---|---|---|
| Project engine §5 | create/open/list/import/version/restore | rename/duplicate/delete/autosave/backup/recovery | CP-71 |
| Project graph §6 | Media/Timeline/Tracks/Clips/Audio | +Text/Captions/Effects/Transitions/Masks/Keyframes/Color/AI ops/Export | CP-71–79 |
| Media engine §7 | MP4/MOV/MKV/WebM/3GP, PNG/JPG/WebP/GIF, MP3/WAV/M4A/OGG/FLAC | +AVI/M4V/SVG probe-honest | CP-88 |
| Timeline §11–12 | multi-track + addClip | split/trim/move/delete/duplicate, markers, lock/mute/hide, transform | CP-72–73 |
| Audio §28 | volume/fade/trim/concat/gain | normalize/noise-gate/EQ/comp/limiter/reverb/voice-change/beat | CP-81 |
| Captions §24–25 | SRT make/shift/burn | STT (SpeechRecognizer) + word timing + แปล + transcript editor + filler | CP-82 |
| Render §72–73 | concat + AVC + AAC + MediaCodec HW | +720p→1080p/4K/fps/codec-honest, effect chain | CP-73–79, CP-83 |
| Export §70–71 | MP4 + MediaStore | presets social + batch + custom | CP-83 |
| QC §39/§84 | file/duration/height/audio | black-frame/clipping/safe-zone/missing + fix loop (max retry) | CP-84 |
| AI §78–82 | router + fallback + providers + local bootstrap | project context + memory prefs + director workflow | CP-84 |
| Undo §87 | version snapshots | operation transactions + undo/redo stack + events §110 | CP-71 |
| Tools §35–36 | registry + gateway + executors | undo-op metadata ทุก tool | CP-71 |
| Permissions §85–86 | grants/task/one-shot/deny | AI autonomy levels 0–4 + per-tool policy | CP-84 |

## ไม่มี → ใส่เพิ่ม

| ส่วน | CP |
|---|---|
| Transaction/Undo/Events/Checkpoint §37/§87/§88/§110/§111 | CP-71 |
| Text engine §22 (+AI text §23) | CP-74 |
| Speed §13 | CP-75 |
| Keyframes §14 | CP-76 |
| Transitions §21 + Effects §20 | CP-77 |
| Color §42 (+histogram §101) | CP-78 |
| Mask §17 + Chroma §18 + BG §19 | CP-79 |
| Tracking-lite + stabilize §15/§43 (template-matching, ซื่อสัตย์ไม่มี ML) | CP-80 |
| Templates §51–52 + Brand §64 + Safe zone §65 | CP-83 |
| Studio UI §94 + modes §3 + AI panel §96 + home §4 | CP-85 |
| Proxy §74 + cache §75 + bg jobs §76 | CP-86 |
| AI modes §32/§40/§41/§50/§57–63/§66–68 | CP-87 |
| Media library §8 + smart search §10 (offline signals + provider vision) | CP-88 |
| Generative §45–46 (provider-wired เท่าที่ provider มีจริง) | CP-89 |
| Recording §54–56 + mixer UI §102 + multicam-lite §103–104 | CP-90 |
| Shortcuts/accessibility/resource/privacy/polish §99–100/§115–119 | CP-91 |

## ซื่อสัตย์-ขอบเขต (ไม่แกล้งมี)

- §9 vision หนัก (detection/OCR/embedding): ใช้ provider vision ตอน online; offline มีแค่ scene/silence/loudness signals.
- §15–16 tracking: template-matching บน CPU (ไม่มีโมเดล ML ในแอป); planar/camera-tracking = MISSING ซื่อสัตย์.
- §24 STT: Android SpeechRecognizer (ตรงสเปก multi-lang ตามเครื่อง); word-timing ประมาณจาก duration.
- §43 upscale/denoise AI, §44 beauty, §47 lip-sync, §49 avatar: วาง interface (architecture-ready) แต่ runtime = MISSING ซื่อสัตย์.
- §46 generative: ต่อเมื่อ provider ที่ต่ออยู่รองรับจริง (text/image/voice/music) — ไม่มี provider ปลอม.
- §89–90/§92–93 cloud/collab/marketplace: local-first เสร็จก่อน; cloud = optional bundle export/import; Supabase คง CP-68 devtool.
- §72 FFmpeg: ยังไม่เอาเข้า (MediaCodec ครอบคลุมงานปัจจุบัน); ประเมินใหม่ถ้า format ไหนทำไม่ได้จริง.
- §120: ฟรี ไม่มี watermark (§134 DoD) — คงไว้ทุก CP.
