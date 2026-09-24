# BUILT-IN AI AUDIT (§24 of แก้250969.txt)

> ตรวจ codebase 2026-09-24 หลังคำสั่ง HARD REQUIREMENT — AI ต้องมากับแอป
> เปิดใช้ได้ทันที ห้ามมีดาวน์โหลดโมเดลเป็นทางผ่าน

## CURRENT ARCHITECTURE

```
APK (~40MB, ไม่มีโมเดล) → ติดตั้ง → เปิดแอป → Chat เรียก LocalChatBrain
→ AiRuntimeManager.initialize() → models.active() → Missing → OFFLINE
→ ผู้ใช้เห็นการ์ด "ดาวน์โหลดและติดตั้ง" (CP-139) → โหลด ~400MB
→ installActiveModel → loadWithResources → JNI load → READY → แชท
```

- Base model: Qwen2.5-0.5B-Instruct Q4_K_M (Apache-2.0, ทางการ HuggingFace)
- Runtime: llama.cpp v0.4.1 (MIT) บิลด์ arm64 ใน CI + `aicode_jni.cpp` + `JniAiRuntime`
- Template: ChatML Qwen2.5 (`ChatTemplate.qwen25`) + `singleTurn`
- RAM gate: `ResourceManager.canLoad` (Ok/Degrade/Refuse)
- Tools/Memory/Agent: Gateway + FileMemoryStore + Orchestrator (มีแล้ว)
- External providers: มีแต่เป็น stub/optional (ไม่บังคับ)

## WHAT IS WRONG

| # | ปัญหา | หลักฐาน | ผล |
|---|---|---|---|
| W1 | **Double ChatML template** — `AiRuntimeManager.chat` ส่ง prompt ที่ห่อ ChatML แล้วเข้า `JniAiRuntime.generate` ซึ่งห่อ `singleTurn` ซ้ำอีกชั้น | `AiRuntimeManager.kt:133` + `JniAiRuntime.kt:80` | โมเดลเห็น ChatML ซ้อนกัน → ตอบว่าง/มั่ว = **"โหลดโมเดลแล้ว AI ใช้ไม่ได้"** (ตรงกับที่เจ้าของเจอ) |
| W2 | Empty generation ถูกถือว่า success — `text.isEmpty() && err.isEmpty()` คืน `GenResult("")` เงียบ ๆ | `JniAiRuntime.kt:85` | ผู้ใช้เห็นข้อความว่าง ไม่รู้ว่าเสีย |
| W3 | Built-in ไม่มีจริง — โมเดลต้องดาวน์โหลดหลังติดตั้ง (CP-139 banner) | `AicodeNav.kt` + `ModelDownload.kt` | ผิด HARD REQUIREMENT §1/§16/§22 (FAIL 1, 8, 11) |
| W4 | `initialize()` ผูกกับ ModelManager 100% — ไม่มีโมเดลใน catalog = OFFLINE | `AiRuntimeManager.kt:57-72` | ผิด §7 (Model Manager ต้องเป็น optional) |
| W5 | Real inference ไม่เคยถูกพิสูจน์ — kc/CI ใช้ Fake; CI เช็คแค่ .so คอมไพล์ผ่าน | `FakeAiRuntime`, `android.yml` | W1 จึงหลุดมาถึงมือผู้ใช้ |
| W6 | ไม่มี OUR-AI identity — system prompt มาจาก template default ภาษาอังกฤษล้วน | `ModelProvider.kt:50-71`, `LocalChatBrain` ไม่ prepend system | ผิด §6 (ต้องเป็น AI ของแอป) |
| W7 | About ไม่มี AI attribution (Qwen/llama.cpp license) | `SettingsScreen.kt:171` | ผิด §19 (NOTICE) |

สิ่งที่ไม่ผิด: JNI/llama.cpp (`aicode_jni.cpp` ใช้ API v0.4.1 ถูกต้อง),
RAM gate (สมเหตุสมผล), GGUF validation, tools/memory/agent,
no-Termux (ใช้ shell ในแอป), noCompress gguf + gitignore (เตรียมไว้แล้ว)

## WHAT MUST BE REMOVED

- R1: `JniAiRuntime.applyTemplate` + การห่อ template ชั้นที่สองใน `generate`
- R2: การ์ดดาวน์โหลดโมเดลหลักครั้งแรก (CP-139 banner) — แทนด้วยการ์ดสถานะ
      built-in (progress ตอนแตกไฟล์ครั้งแรก ไม่ใช่ดาวน์โหลด)
- R3: ข้อความชวนดาวน์โหลดใน `LocalChatBrain.offlineGuidance`
      ("ใช้ model.download เพื่อโหลด Qwen…")

## WHAT MUST BE MODIFIED

- M1: `JniAiRuntime.generate` — ส่ง prompt ตรง ไม่ห่อซ้ำ; ตอบว่าง = Failure
- M2: `LocalChatBrain` — prepend system prompt "AicodeMax + ตอบภาษาไทย"
- M3: `AiRuntimeManager` — รองรับ built-in path (`expectBuiltin`,
      `provisionProgress`, `loadBuiltin`, `builtinMissing`) โดยไม่ผ่าน ModelManager
- M4: `ModelsScreen` — ส่วน Built-in (สถานะ/error/RAM/recover) + คงส่วน optional ไว้
- M5: `AboutScreen` — เพิ่ม AI attribution (Qwen Apache-2.0, llama.cpp MIT)
- M6: `android.yml` — ดาวน์โหลดโมเดลตอนบิลด์ + แพ็กเป็น asset + host smoke test
- M7: เทส `JniAiRuntimeTest` — assert passthrough (เลิก assert การห่อซ้ำ)

## WHAT MUST BE CREATED

- C1: `app/.../BuiltinAiProvisioner.kt` — แตก asset → filesDir (ครั้งเดียว,
      มี progress) → validate GGUF → `loadBuiltin` (ไม่ใช้เน็ต)
- C2: CI host smoke test — บิลด์ llama-cli ฝั่ง host + รัน "สวัสดี" ผ่านไฟล์
      โมเดลที่จะแพ็กจริง (พิสูจน์ weights+runtime ก่อนแพ็ก)
- C3: `BuiltinAiCard` แทน `ModelDownloadBanner` (สถานะ ไม่ใช่ปุ่มโหลด)
- C4: เอกสารนี้ + แถว CP-144 ใน CHECKPOINTS

## FINAL ARCHITECTURE

```
CI: ดาวน์โหลด Qwen2.5-0.5B Q4_K_M (Apache-2.0 ✓) → smoke test จริง
  → แพ็กเป็น assets/ai/builtin-model.gguf → APK (~410MB)

ติดตั้ง → เปิดแอป (ออฟไลน์ได้) → แตก asset ครั้งเดียว (progress ในเครื่อง)
→ loadBuiltin (ไม่ผ่าน ModelManager) → READY → พิมพ์ "สวัสดี"
→ single ChatML + system "AicodeMax/ไทย" → llama.cpp inference จริง → ตอบ

Model Manager = optional (อัปเกรด/โมเดลเฉพาะทาง/external) เท่านั้น
```

## DoD MAPPING (§21)

- ไม่มีดาวน์โหลด/คีย์/Termux/runtime เสริม ✓ (asset+so มากับ APK)
- เปิด→init→แชท→ตอบจริง→ออฟไลน์ได้ ✓ (C1+M3+M1+M2; smoke C2 พิสูจน์ inference)
- App context/tools/memory ✓ (มีแล้ว: gateway/LearningEngine/Orchestrator)
- Error handling ✓ (M1 empty=Failure + M4 แสดง error จริง)
- Clean-install/offline-first-launch/no-hidden-download/no-mock ✓ (ดีไซน์นี้;
  ผู้ใช้ทดสอบบนอุปกรณ์จริงยืนยันขั้นสุดท้าย)

## เรื่อง STT (whisper)

ไฟล์ข้อกำหนดพูดถึง core AI (แชท) — whisper เป็น specialized model
ตาม §7 จึงคงแบบ one-tap ใน Model Manager ไว้ก่อน (ไม่ bundle รอบนี้)
