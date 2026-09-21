# ROADMAP P0–P30 — สถานะหลัง sprint วันที่ 2026-09-21

ตำนาน: ✅ เสร็จวันนี้ / 🟡 โครง+contract เสร็จ รอ impl บนอุปกรณ์ / ⬜ รอบหน้า

| Phase | งาน | สถานะ | หลักฐาน |
|---|---|---|---|
| 0 | Architecture Contract | ✅ | docs/arch (6 ไฟล์นี้) |
| 1 | Native Shell | ✅โครง | :app + Gradle + CI ประกอบได้ (รอ build บน CI/Studio) |
| 2 | State/Event | ✅ | :core:common/state + test ผ่าน (JVM) |
| 3 | Resource Manager | 🟡 | interface + Android impl (รอเทสบนเครื่อง) |
| 4 | Local AI Runtime | 🟡 | port + router + honest status (รอ model file) |
| 5 | Model Manager | 🟡 | registry/routing/fallback (รอ model file) |
| 6–7 | AI Core/Context | 🟡 | Orchestrator/Intent/Planner ports + HonestBootstrap |
| 8 | Memory | ✅ | file MemoryStore + test ผ่าน |
| 9 | Planner | 🟡 | port + rule-based v0 (ต่อ LLM ภายหลัง) |
| 10 | Task Engine | ✅ | state machine จริง + test ผ่าน |
| 11 | Agent Runtime | 🟡 | port + LocalAgentRunner v0 |
| 12 | Tool Registry | ✅ | registry จริง + test ผ่าน |
| 13 | Tool Gateway | 🟡 | pipeline จริง (permission impl บนเครื่อง) |
| 14 | File System | 🟡 | impl จริงบน app-storage (รอเทสบนเครื่อง) |
| 15 | Code Editor | 🟡 | session contract + file-backed buffer |
| 16 | Terminal | 🟡 | port + AIControlAPI + แผน submodule (ต่อ Termux รอบหน้า) |
| 17 | Build/Test | 🟡 | contracts (on-device build รอบหน้า) |
| 18 | Git/GitHub | 🟡 | contracts (JGit/CLI รอบหน้า) |
| 19 | Browser Runtime | 🟡 | tab/session contracts (WebView impl รอบหน้า) |
| 20 | Verification | 🟡 | Verifier port + rule checks v0 |
| 21 | Checkpoint/Recovery | ✅ | file store จริง + test ผ่าน |
| 22 | Security/Permission | 🟡 | AuditLog จริง+test; permission บนเครื่อง |
| 23 | Chat UI | ✅โครง | Compose Chat จริง + honest engine states |
| 24 | Workspace UI | ✅โครง | gate ตาม capability จริง |
| 25 | Settings | ✅โครง | DataStore + About/legal จริง |
| 26 | Integration | 🟡 | ServiceLocator + CI build |
| 27–29 | Stress/Offline/Low-RAM | ⬜ | ต้องรันบนอุปกรณ์จริง |
| 30 | Release Candidate | ⬜ | หลัง 27–29 ผ่าน |

รอบถัดไป (ต้องใช้อุปกรณ์/Studio): ต่อ Termux submodule, ฝัง bootstrap model,
WebView browser, on-device build/git, แล้วรัน 27–30.
