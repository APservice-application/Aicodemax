# Hardening passes (CP-133, audit P21–24)

How offline / low-RAM / crash / device cases are proven. Unit passes run in
`kc-build` (JVM) and CI (`compileDebugUnitTestKotlin` + `testDebugUnitTest`);
the device pass is a manual checklist executed on a real phone with the
release APK.

## P21 — Offline (no network)

| Layer | Behavior | Test |
|---|---|---|
| Chat brain | OFFLINE state → Thai guidance to download a model first | `LocalChatBrainTest` (CP-128) |
| Validation | `network` precondition unmet → `model.download` rejected before dispatch | `ToolCallValidatorTest.offlineBlocksNetworkPrecondition` |
| Download | `JavaNetModelDownloader` IO failure → honest error, resumable `ModelStore` | `ModelStoreTest` (CP-120) |

## P22 — Low RAM / 32-bit

| Layer | Behavior | Test |
|---|---|---|
| Load gate | `canLoad` → `Ok` / `Degrade` (smaller ctx) / `Refuse` with Thai reason | `ResourceManagerTest` (CP-127) |
| 32-bit | `Refuse("ต้องใช้ CPU 64-bit")`, never attempts load | `ResourceManagerTest.canLoadRefuses32Bit` |
| Pressure | `pressure()` NORMAL/WARNING/CRITICAL; CRITICAL blocks `recover()` | `AiRuntimeManagerTest` (CP-127) |

## P23 — Crash / recovery

| Layer | Behavior | Test |
|---|---|---|
| Marker | `runtime/session.marker` written on `begin()`, removed on clean `endClean()` | `SessionMarkerTest` (CP-126) |
| Detection | stale marker at startup → `crashedLastRun == true` → guarded recover | `AiRuntimeManagerTest` (CP-126) |
| UI | ERROR/OFFLINE states + "กู้คืน" button in Models → Runtime | manual (P24) |

## P24 — Device pass (manual, per release APK)

1. Install APK on arm64 Android 8+ (minSdk 26) with no model → chat shows
   offline guidance; Models → Runtime shows OFFLINE.
2. Tap ติดตั้ง+โหลดโมเดลหลัก on Wi-Fi → progress % → READY.
3. Chat "สวัสดี" → THINKING → Thai reply; stop button halts generation.
4. Tool turn (e.g. "อ่านไฟล์…") → RUNNING_TOOL with tool name; Audit shows
   `tool.trace` retrieve→decide→validate→result lines.
5. Airplane mode → `model.download` blocked with precondition message.
6. Force-stop mid-generation → relaunch → no crash loop; Runtime recovers.
7. Low-end device (≤3GB RAM): load degrades ctx or refuses with Thai reason.
