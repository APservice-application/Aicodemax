# TERMINAL WIRING — ผังเสียบ Termux (Phase 16)

ศึกษาจาก `termux/termux-app` (shallow ref, 2026-09-21) แล้วออกแบบจุดต่อไว้ล่วงหน้า
โค้ดจริงเสียบตอนมี submodule (ไม่ทำตอนนี้เพราะประหยัดพื้นที่ 128MB)

## 1. ชิ้นส่วน Termux ที่เราจะใช้ (API ที่ยืนยันแล้ว)

| ชั้นเรา | คลาส Termux | API สำคัญ |
|---|---|---|
| session/process | `com.termux.terminal.TerminalSession` | `TerminalSession(shellPath, cwd, args, env, transcriptRows, client)`, `write(byte[],off,len)`, `isRunning()`, `getExitStatus()`, `getPid()`, `initializeEmulator/updateSize`, `finishIfRunning()` |
| output observe | `TerminalSessionClient` | `onTextChanged`, `onTitleChanged`, `onSessionFinished`, `setTerminalShellPid` |
| จอ terminal (Phase 24) | `com.termux.view.TerminalView` | view ฝังใน Compose ผ่าน `AndroidView` |
| PTY native | `com.termux.terminal.JNI` + `terminal-emulator/src/main/jni/termux.c` | ⚠️ ต้องมี **NDK** ตอน build (ดูข้อ 4) |
| สร้าง session | `TermuxService.createTermuxSession(...)` | ห่อ env + runner; เราเลียนแบบด้วย service ของตัวเอง (ข้อ 5) |
| ติดตั้งชุดคำสั่ง | `TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone)` | เช็ก $PREFIX → staging → แตก zip + ตั้ง exec perms (ข้อ 3) |

## 2. แผนที่ port ของเรา ↔ Termux

```
tools:terminal (ports, มีแล้ว)
  TerminalPort.createSession  →  new TerminalSession(loginShell, cwd, args, env, ROWS, client)
  TerminalPort.exec           →  session.write(cmd.bytes) + เก็บ onTextChanged จน onSessionFinished
  TerminalPort.closeSession   →  session.finishIfRunning()
  AIControlAPI.runCommand     →  exec แบบรวมผล (exit code + stdout/stderr) — ใช้ใน orchestrator
tools:terminal_runtime (มีแล้ว: executor จริง + UnwiredTerminalPort)
  TerminalToolExecutor        →  พร้อมใช้ทันทีที่ TerminalPort เป็นของจริง (ไม่ต้องแก้)
  UnwiredTerminalPort         →  ตัวแทนซื่อสัตย์: ตอบ TERMINAL_UNWIRED จนกว่าจะเสียบ submodule
```

## 3. Bootstrap (ชุดคำสั่ง Linux)

- Flow ตาม `TermuxInstaller`: มี $PREFIX แล้วจบ → ไม่มีก็โหลด zip → แตกเข้า staging → ย้ายเข้าที่ + exec perms
- ตามสเปก §20: **ดาวน์โหลดแบบ on-demand หลังติดตั้ง** (ไม่แถมใน APK) + แถบ progress + verify
- 🔶 จุดเปิด: constants ของ Termux อ้าง path ของแพ็กเกจ Termux — ตอนเสียบต้องปรับให้ใช้
  path ของแอปเรา (`filesDir`-based) ผ่าน shell environment ของเราเอง

## 4. สิ่งที่ต้องเพิ่มตอนเสียบ submodule (เช็กลิสต์ Phase 16)

```bash
# 1) เพิ่ม submodule (ทำบนเครื่อง dev/CI ไม่ใช่ workspace 128MB นี้)
git submodule add --depth 1 https://github.com/termux/termux-app.git vendor/termux-app
```

```kotlin
// 2) settings.gradle.kts — รวมโมดูล Termux เป็น Gradle modules
include(":termux:emulator")
project(":termux:emulator").projectDir = file("vendor/termux-app/terminal-emulator")
include(":termux:view")
project(":termux:view").projectDir = file("vendor/termux-app/terminal-view")
```

```kotlin
// 3) tools/terminal_runtime/build.gradle.kts — พึ่งของจริง
// implementation(project(":termux:emulator"))
// implementation(project(":termux:view"))
```

```kotlin
// 4) เขียน TermuxTerminalPort : TerminalPort ของจริง แล้วลงทะเบียนแทน UnwiredTerminalPort
// 5) NDK: ใส่ ndkVersion ใน android{} + ติดตั้ง NDK ใน CI (คอมไพล์ jni/termux.c)
// 6) Foreground service โฮสต์ session (ตามแบบ TermuxService) + กฎ Android 12+
// 7) หน้า Terminal UI (Phase 24) ด้วย TerminalView + โหมด headless ให้ AI
// 8) อัปเดต descriptor: RUNTIME/EXECUTION → AVAILABLE เมื่อทุกข้อผ่าน + เขียนเทส
```

## 5. หมายเหตุสถาปัตยกรรม

- AI สั่งผ่าน `AIControlAPI` (headless) ส่วนคนใช้ผ่าน `TerminalView` — two-way ตามสเปก 1.1
- ทุกคำสั่งผ่าน Gateway เดิม (permission + audit) — คำสั่งอันตราย (`pkg install`, ลบทั้งก้อน)
  ให้ planner ตั้ง `needsPermission=true` เสมอ
- Session อยู่รอดข้ามจอ: เก็บใน service ไม่ใช่ ViewModel (กัน process death ตาม 1.2 §148)
