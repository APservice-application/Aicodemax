# Audit Reports (CP-49..CP-54)

Date: 2026-09-21. All audits performed statically + by unit/integration tests below.
Local suite: **183/183** (`/home/user/kc-build.sh`). CI must confirm on device toolchains.

## CP-49 — Error/Empty/Loading/Permission UX Audit: PASS

Every route was checked for empty / loading / error / denied states:

| Route | Empty | Loading/working | Error | Permission-denied |
|---|---|---|---|---|
| Home | n/a (always renders) | "กำลังอ่านทรัพยากร…" | inline line | n/a |
| Chat | EmptyChat + suggestions | SendingRow + หยุด button | inline error | denial arrives as STATUS msg |
| Projects | "ว่างเปล่า — สั่ง AI…" | editor "กำลังโหลด…" | inline error | via gateway errors |
| Tasks | "ยังไม่มีงาน" | event-driven refresh | inline actionError | approve/deny buttons |
| Models | "ยังไม่มีโมเดล" | working flag on health | note line | n/a |
| Tools | registry always non-empty | "กำลังทดสอบ…" | verdict line | n/a |
| Settings | defaults via Flow initial | n/a | n/a | revoke-all + audit link |
| Audit | "ยังไม่มีรายการ" | refresh button | inline error | filter: denied-only |
| Browser | "ยังไม่มีแท็บ" | WebView progress via tab.loading | WebView default page | n/a |
| Terminal | "(ยังไม่มีผลลัพธ์)" | running flag | ❌ code: message | BANNED never sent |
| Agents | "(unreachable) ยังไม่มี agent" | n/a | n/a | allowlist shown per agent |
| Git (+GitHub tab) | "ยังไม่ใช่ git repo" + Init; GitHub: empty issues note | per-action reload; GitHub "กำลังโหลด…" | inline error | token memory-only note |
| Build | "(ยังไม่เคยรัน)" | per-run reload | inline error | n/a |
| Approval dialog | n/a | FIFO queue, timeout=deny | timeout message in chat/gateway | the dialog itself |

No dead-end screens: every empty state tells the user what to do next.

## CP-50 — Security Audit: PASS (1 fix applied, 1 standing user action)

- ✅ No hardcoded secrets (`ghp_|gho_|sk-|AKIA|password=` grep clean).
- ✅ ProcessRunner uses argv arrays (no shell) — command injection impossible by construction.
- ✅ SandboxFileStore resolves via canonical path containment (`PATH_ESCAPE` otherwise).
- ✅ Zip handling is zip-slip guarded in one place (`ZipArchive`, CP-03).
- ✅ FIXED: `GitCredentials.toString()` redacted (`secret=***`) — credentials can't leak via logs/crash reports.
- ✅ Approval flow defaults to DENY (timeout, missing UI, unknown actions are RISKY).
- ✅ Manifest: only INTERNET + ACCESS_NETWORK_STATE; MainActivity export is the required LAUNCHER entry.
- ✅ WebView: JavaScript on (needed), no `addJavascriptInterface`, no file access enabled.
- ⚠️ STANDING USER ACTION (unchanged): revoke the two chat-exposed PATs; they were never written to the repo.
- ℹ️ `allowBackup=true` keeps audit/models in system backups — acceptable for v0, revisit before Play release.

## CP-51 — Architecture Duplication Audit: PASS

- ✅ Zip: single owner (`ZipArchive`); BackupManager + File Engine both delegate.
- ✅ SHA-256: single owner (`ArtifactStore.sha256Of`).
- ✅ Outcome/fold/runOutcome: single owner (`core/common`), used consistently.
- ℹ️ `FakeClock` ×7 and per-store `readAll`/`readIndex` helpers are intentionally local (test isolation / different models) — not merged by design.
- ℹ️ No second implementation of any engine found (one `TaskEngine` impl, one `GitPort` impl, one `FilePort` impl, one gateway).

## CP-52 — Integration Test: PASS

`GatewayIntegrationTest` drives the REAL pipeline end-to-end with zero fakes:
files.write → editor.preview → git ensure/stage/commit/log → browser open/list,
then asserts ground truth on disk (file bytes + `.git/`). Runs in CI
(`:tools:gateway:testDebugUnitTest`) and in the local suite.

## CP-53 — Stress + Low RAM + Restart: PASS (unit scope)

- `AuditVolumeTest`: 2,000 appends stay queryable (newest-first page + denied filter counts exact); rotation archives and service continues.
- `ResourceModes` (CP-48): RAM/storage/battery/network thresholds derive OFFLINE + LOW_RESOURCE deterministically (unit-tested).
- Restart: every store is file-backed with roundtrip tests (memory, conversations, checkpoints, audit, projects, history, settings via DataStore).
- Device-level low-RAM kill/restart still needs a manual pass on hardware (documented, not faked).

## CP-54 — Release Candidate Audit: v0.1.0 (foundation) GO

- ✅ versionCode 1 / versionName 0.1.0, minSdk 26, targetSdk 34.
- ✅ LICENSE (GPLv3) + THIRD_PARTY.md + About screen attribution (AMENDMENT-002).
- ✅ Minify off for alpha (documented; enable with rules before Play).
- ✅ CI builds debug APK + runs JVM and Android unit tests.
- ✅ Honest capability descriptors on every tool (no fake-done anywhere).
- ⏳ CP-55 owner sign-off still required — that is the actual release gate.
